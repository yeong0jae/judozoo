# ---------------------------------------------------------------------------
# 필요한 API 활성화 (기존 trading 인프라가 이미 켜둔 API와 동일 — disable_on_destroy=false로 안전)
# ---------------------------------------------------------------------------
resource "google_project_service" "apis" {
  for_each = toset([
    "compute.googleapis.com",
    "artifactregistry.googleapis.com",
    "secretmanager.googleapis.com",
    "iam.googleapis.com",
    "iamcredentials.googleapis.com",
    "sts.googleapis.com",
  ])
  service            = each.value
  disable_on_destroy = false
}

# ---------------------------------------------------------------------------
# 인스턴스 정의
# ---------------------------------------------------------------------------
locals {
  instances = {
    "kiwoom-real" = { name_suffix = "-kiwoom-real" }
  }
}

# ---------------------------------------------------------------------------
# 고정 외부 IP (프론트 공개용 / KIS·KIWOOM IP 허용목록 대비)
# ---------------------------------------------------------------------------
resource "google_compute_address" "frontend" {
  for_each = local.instances
  name     = "auto-trading-frontend-ip${each.value.name_suffix}"
  region   = var.region

  depends_on = [google_project_service.apis]
}

# ---------------------------------------------------------------------------
# VM 런타임 서비스계정 (AR pull + Secret 접근)
# ---------------------------------------------------------------------------
resource "google_service_account" "vm" {
  account_id   = "auto-trading-vm"
  display_name = "autonomous-trading VM runtime SA"
}

resource "google_project_iam_member" "vm_ar_reader" {
  project = var.project_id
  role    = "roles/artifactregistry.reader"
  member  = "serviceAccount:${google_service_account.vm.email}"
}

resource "google_project_iam_member" "vm_secret_accessor" {
  project = var.project_id
  role    = "roles/secretmanager.secretAccessor"
  member  = "serviceAccount:${google_service_account.vm.email}"
}

# ---------------------------------------------------------------------------
# 방화벽: 프론트 tcp:3000 / SSH는 IAP 대역만
# 기존 trading 인스턴스와 분리하기 위해 별도 target_tag 사용
# ---------------------------------------------------------------------------
resource "google_compute_firewall" "web" {
  name    = "auto-trading-allow-web"
  network = "default"

  allow {
    protocol = "tcp"
    ports    = ["3000", "3001"]
  }

  source_ranges = var.allowed_web_source_ranges
  target_tags   = ["auto-trading"]

  depends_on = [google_project_service.apis]
}

resource "google_compute_firewall" "ssh" {
  name    = "auto-trading-allow-ssh"
  network = "default"

  allow {
    protocol = "tcp"
    ports    = ["22"]
  }

  # IAP TCP forwarding 전용 대역 + 허용된 IP 대역
  source_ranges = concat(["REDACTED_IP/20"], var.allowed_web_source_ranges)
  target_tags   = ["auto-trading"]

  depends_on = [google_project_service.apis]
}

# ---------------------------------------------------------------------------
# VM (Ubuntu, Docker/Compose 설치 startup-script)
# ---------------------------------------------------------------------------
resource "google_compute_instance" "app" {
  for_each     = local.instances
  name         = "auto-trading-app${each.value.name_suffix}"
  machine_type = var.machine_type
  zone         = var.zone
  tags         = ["auto-trading"]

  boot_disk {
    initialize_params {
      image = "ubuntu-os-cloud/ubuntu-2404-lts-amd64"
      size  = var.boot_disk_size_gb
    }
  }

  network_interface {
    network = "default"
    access_config {
      nat_ip = google_compute_address.frontend[each.key].address
    }
  }

  service_account {
    email  = google_service_account.vm.email
    scopes = ["cloud-platform"]
  }

  metadata = {
    enable-oslogin = "TRUE"
    startup-script = file("${path.module}/startup.sh")
  }

  depends_on = [google_project_service.apis]
}

# ---------------------------------------------------------------------------
# Artifact Registry (Docker) — 기존 trading 레포와 분리
# ---------------------------------------------------------------------------
resource "google_artifact_registry_repository" "docker" {
  location      = var.region
  repository_id = "auto-trading"
  format        = "DOCKER"

  depends_on = [google_project_service.apis]
}

# ---------------------------------------------------------------------------
# Secret Manager — 컨테이너만 생성. 실제 값은 수동 주입.
#   echo -n "<value>" | gcloud secrets versions add AT_* --data-file=-
# ---------------------------------------------------------------------------
locals {
  app_secrets = toset([
    "AT_DB_PASSWORD",
    "AT_KIWOOM_APP_KEY",
    "AT_KIWOOM_APP_SECRET",
    "AT_KIWOOM_ACCOUNT_NO",
  ])
}

resource "google_secret_manager_secret" "app" {
  for_each  = local.app_secrets
  secret_id = each.value

  replication {
    auto {}
  }

  depends_on = [google_project_service.apis]
}

# ---------------------------------------------------------------------------
# GitHub Actions 배포 SA + Workload Identity Federation (장기 키 미사용)
# ---------------------------------------------------------------------------
resource "google_service_account" "deployer" {
  account_id   = "auto-trading-gha-deployer"
  display_name = "autonomous-trading GitHub Actions deployer SA"
}

resource "google_project_iam_member" "deployer_ar_writer" {
  project = var.project_id
  role    = "roles/artifactregistry.writer"
  member  = "serviceAccount:${google_service_account.deployer.email}"
}

resource "google_project_iam_member" "deployer_compute_admin" {
  project = var.project_id
  role    = "roles/compute.instanceAdmin.v1"
  member  = "serviceAccount:${google_service_account.deployer.email}"
}

# osAdminLogin: VM에서 sudo(docker compose 실행)까지 필요 → osLogin 대신 admin
resource "google_project_iam_member" "deployer_os_login" {
  project = var.project_id
  role    = "roles/compute.osAdminLogin"
  member  = "serviceAccount:${google_service_account.deployer.email}"
}

resource "google_project_iam_member" "deployer_iap" {
  project = var.project_id
  role    = "roles/iap.tunnelResourceAccessor"
  member  = "serviceAccount:${google_service_account.deployer.email}"
}

# deployer가 VM SA로 act-as 할 수 있어야 인스턴스 갱신 가능
resource "google_service_account_iam_member" "deployer_actas_vm" {
  service_account_id = google_service_account.vm.name
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${google_service_account.deployer.email}"
}

resource "google_iam_workload_identity_pool" "github" {
  workload_identity_pool_id = "auto-trading-pool"
  display_name              = "Auto-trading GHA pool"

  depends_on = [google_project_service.apis]
}

resource "google_iam_workload_identity_pool_provider" "github" {
  workload_identity_pool_id          = google_iam_workload_identity_pool.github.workload_identity_pool_id
  workload_identity_pool_provider_id = "auto-trading-provider"
  display_name                       = "autonomous-trading GitHub OIDC"

  attribute_mapping = {
    "google.subject"       = "assertion.sub"
    "attribute.repository" = "assertion.repository"
  }

  # 지정한 저장소만 토큰 교환 허용
  attribute_condition = "assertion.repository == \"${var.github_repository}\""

  oidc {
    issuer_uri = "https://token.actions.githubusercontent.com"
  }
}

resource "google_service_account_iam_member" "deployer_wif" {
  service_account_id = google_service_account.deployer.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github.name}/attribute.repository/${var.github_repository}"
}
