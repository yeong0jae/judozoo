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
    "storage.googleapis.com",
    "sqladmin.googleapis.com",          # Cloud SQL
    "servicenetworking.googleapis.com", # Cloud SQL private IP용 VPC 피어링
  ])
  service            = each.value
  disable_on_destroy = false
}

# ---------------------------------------------------------------------------
# 인스턴스 정의
# ---------------------------------------------------------------------------
locals {
  # **키(`kiwoom-real`)를 바꾸지 않는다.** google_compute_address.frontend 가 같은 키를
  # 쓰므로, 키를 건드리면 고정 외부 IP까지 재생성된다. 그러면 Cloudflare A 레코드와
  # 키움 IP 허용목록을 둘 다 갱신해야 한다 — 이름 정리하자고 치를 값이 아니다.
  #
  # 키는 3인스턴스(kis-vts/kis-real/kiwoom-real) 시절 잔재이고 지금은 인스턴스가 하나다.
  instances = {
    "kiwoom-real" = {
      name_suffix = "-kiwoom-real" # 고정 IP 이름에 계속 쓰인다
      vm_name     = "judozoo-server-prod"
    }
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
  display_name = "judozoo VM runtime SA"
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
# 방화벽: Caddy tcp:80,443만 / SSH는 IAP 대역만
# 기존 trading 인스턴스와 분리하기 위해 별도 target_tag 사용
#
# 앱 컨테이너는 호스트에 포트를 퍼블리시하지 않는다. 유일한 입구가 Caddy다.
# ---------------------------------------------------------------------------
resource "google_compute_firewall" "web" {
  name    = "auto-trading-allow-web"
  network = "default"

  allow {
    protocol = "tcp"
    ports    = ["80", "443"]
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
    # 22만. Grafana(3000)는 019에서 ops VM으로 떠났고 그쪽 규칙이
    # judozoo-ops-allow-iap 다 — 여기 남겨두면 쓰지도 않는 포트가 열린 채 있다.
    ports = ["22"]
  }

  # **IAP 대역만.** allowed_web_source_ranges를 같이 쓰면 안 된다 —
  # 앱을 0.0.0.0/0으로 공개하는 순간 SSH까지 인터넷 전체에 열린다.
  # 배포·운영 접속은 전부 IAP 터널(--tunnel-through-iap)을 거치므로 이것만 있으면 된다.
  source_ranges = ["REDACTED_IP/20"]
  target_tags   = ["auto-trading"]

  depends_on = [google_project_service.apis]
}

# ---------------------------------------------------------------------------
# Cloud NAT — 외부 IP 없는 VM의 아웃바운드
#
# ops VM(judozoo-ops-prod)에는 외부 IP를 주지 않는다. 그러면 Docker 설치와 apt 보안
# 패치가 막히므로 **나가는 길만** 연다. NAT는 들어오는 연결을 열지 않는다.
#
# 외부 IP가 있는 VM은 NAT를 타지 않고 자기 IP로 그대로 나간다 — 브로커 IP 허용목록이
# 앱 VM의 고정 IP를 보고 있으므로 앱 VM의 동작은 변하지 않는다.
# ---------------------------------------------------------------------------
resource "google_compute_router" "nat" {
  name    = "judozoo-router"
  network = data.google_compute_network.default.id
  region  = var.region
}

resource "google_compute_router_nat" "nat" {
  name                               = "judozoo-nat"
  router                             = google_compute_router.nat.name
  region                             = var.region
  nat_ip_allocate_option             = "AUTO_ONLY"
  source_subnetwork_ip_ranges_to_nat = "ALL_SUBNETWORKS_ALL_IP_RANGES"

  # 조용하되 실패는 남긴다. 전체 로깅은 양이 많고 볼 일도 없다.
  log_config {
    enable = true
    filter = "ERRORS_ONLY"
  }
}

# ---------------------------------------------------------------------------
# ops VM 방화벽
#
# **`auto-trading` 태그를 ops VM에 달지 않는다.** 그 태그는 allow-web(0.0.0.0/0 → 80,443)의
# 타깃이라, 외부 IP가 없어도 규칙만큼은 인터넷을 향해 열린 상태가 된다.
#
# 3100은 앱 VM의 alloy가 로그를 밀어 넣는 통로다. default-allow-internal이 VPC 내부
# 전 포트를 이미 열어두고 있어 이 규칙이 실제 경계는 아니지만, 의도를 코드로 남긴다.
# ---------------------------------------------------------------------------
resource "google_compute_firewall" "ops_loki" {
  name    = "judozoo-ops-allow-loki"
  network = "default"

  allow {
    protocol = "tcp"
    ports    = ["3100"]
  }

  source_tags = ["auto-trading"]
  target_tags = ["judozoo-ops"]

  depends_on = [google_project_service.apis]
}

resource "google_compute_firewall" "ops_iap" {
  name    = "judozoo-ops-allow-iap"
  network = "default"

  allow {
    protocol = "tcp"
    # 3000 = Grafana. 공개 서브도메인 없이 IAP 터널로만 연다.
    ports = ["22", "3000"]
  }

  source_ranges = ["REDACTED_IP/20"]
  target_tags   = ["judozoo-ops"]

  depends_on = [google_project_service.apis]
}

# ---------------------------------------------------------------------------
# Cloud SQL private IP 연결 (Private Service Access)
#
# Cloud SQL 인스턴스는 Google이 관리하는 테넌트 프로젝트에서 돌고 VPC 피어링으로
# 우리 네트워크에 붙는다. 공인 IP를 만들지 않으니 인터넷에서 오는 경로가 아예 없고,
# 피어링 트래픽은 VPC 방화벽을 거치지 않으므로 3306 규칙도 필요 없다.
#
# 예약 대역은 auto-mode 서브넷(10.128.0.0/9) **바깥**이어야 한다. 겹치면 피어링이
# 실패하고, 한 번 잡은 대역은 넓히기 번거로우므로 처음부터 /24를 잡는다.
# ---------------------------------------------------------------------------
data "google_compute_network" "default" {
  name       = "default"
  depends_on = [google_project_service.apis]
}

resource "google_compute_global_address" "sql_peering" {
  name          = "judozoo-sql-peering"
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  address       = "10.100.0.0"
  prefix_length = 24
  network       = data.google_compute_network.default.id
}

resource "google_service_networking_connection" "sql" {
  network                 = data.google_compute_network.default.id
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.sql_peering.name]

  # 없으면 destroy가 피어링을 못 떼고 멈춘다. ABANDON은 GCP 쪽 연결을 남긴 채
  # state에서만 분리하므로, 다시 만들 때 기존 연결을 그대로 재사용한다.
  deletion_policy = "ABANDON"
}

# ---------------------------------------------------------------------------
# VM (Ubuntu, Docker/Compose 설치 startup-script)
# ---------------------------------------------------------------------------
resource "google_compute_instance" "app" {
  for_each     = local.instances
  name         = each.value.vm_name
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
# 관측 전용 VM — Loki · Grafana
#
# 용량 때문이 아니다(둘 합쳐 152MB). 앱 VM이 죽어도 **왜 죽었는지 볼 도구는 살아 있게**
# 하려는 것이고, 배포마다 관측 스택이 강제 재생성되는 것을 끊으려는 것이다.
#
# 수집기(alloy)는 앱 VM에 남는다 — docker.sock이 유닉스 소켓이라 남의 호스트 컨테이너를
# 읽지 못한다. 수집은 호스트마다, 저장은 한 곳에.
#
# 별도 PD를 두지 않는다. 대시보드는 git에 있고(프로비저닝 바인드) 로그는 7일짜리라,
# VM을 재생성해도 잃을 것이 사용자 설정과 지난 로그뿐이다. DB와 판단 기준이 다르다.
# ---------------------------------------------------------------------------
resource "google_compute_instance" "ops" {
  name         = "judozoo-ops-prod"
  machine_type = "e2-small"
  zone         = var.zone

  # **`auto-trading`을 달지 않는다.** 그 태그는 allow-web(0.0.0.0/0 → 80,443)의 타깃이다.
  tags = ["judozoo-ops"]

  boot_disk {
    initialize_params {
      image = "ubuntu-os-cloud/ubuntu-2404-lts-amd64"
      size  = var.boot_disk_size_gb
    }
  }

  # access_config 블록이 없다 = 외부 IP 없음. 아웃바운드는 Cloud NAT가 맡는다.
  network_interface {
    network = "default"
  }

  # 앱 VM과 같은 런타임 SA. AR pull·Secret 접근이 이미 붙어 있다.
  service_account {
    email  = google_service_account.vm.email
    scopes = ["cloud-platform"]
  }

  # Docker 설치뿐이라 앱 VM과 같은 스크립트를 쓴다.
  metadata = {
    enable-oslogin = "TRUE"
    startup-script = file("${path.module}/startup.sh")
  }

  depends_on = [google_compute_router_nat.nat]
}

# ---------------------------------------------------------------------------
# Artifact Registry (Docker) — 기존 trading 레포와 분리
# ---------------------------------------------------------------------------
resource "google_artifact_registry_repository" "docker" {
  location      = var.region
  repository_id = "auto-trading"
  format        = "DOCKER"

  # main 푸시마다 이미지가 하나씩 쌓이는데 VM의 `docker image prune`은 VM만 치운다.
  # 레지스트리에는 정책이 없으면 영원히 남는다.
  #
  # KEEP이 DELETE보다 우선한다 — 그래서 "전부 삭제" + "최근 3개는 유지" 두 개를 같이 건다.
  # 롤백은 옛 이미지를 당겨오는 게 아니라 그 커밋의 워크플로를 재실행해 다시 빌드하므로,
  # 3개만 남겨도 되돌릴 방법이 사라지지 않는다.
  cleanup_policies {
    id     = "delete-all"
    action = "DELETE"
    condition {
      older_than = "0s"
    }
  }

  cleanup_policies {
    id     = "keep-recent-3"
    action = "KEEP"
    most_recent_versions {
      keep_count = 3 # 패키지(backend / frontend)별로 각각 3개
    }
  }

  depends_on = [google_project_service.apis]
}

# ---------------------------------------------------------------------------
# DB 백업 버킷
#
# 누적 데이터(분봉·투자자 수급·시그널·테마 스냅샷)는 과거 시점이라 브로커에서 다시 받을 수
# 없다. VM 디스크에 두면 VM과 함께 죽으므로 별도 저장소여야 의미가 있다.
# ---------------------------------------------------------------------------
resource "google_storage_bucket" "db_backup" {
  name     = "${var.project_id}-auto-trading-db-backup"
  location = var.region

  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"

  lifecycle_rule {
    condition {
      age = var.db_backup_retention_days
    }
    action {
      type = "Delete"
    }
  }

  depends_on = [google_project_service.apis]
}

# VM이 덤프를 올릴 수 있어야 한다. 버킷 하나로 범위를 제한한다.
resource "google_storage_bucket_iam_member" "vm_backup_writer" {
  bucket = google_storage_bucket.db_backup.name
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${google_service_account.vm.email}"
}

# ---------------------------------------------------------------------------
# Secret Manager — 컨테이너만 생성. 실제 값은 수동 주입.
#   echo -n "<value>" | gcloud secrets versions add AT_* --data-file=-
# ---------------------------------------------------------------------------
locals {
  # AT_DB_PASSWORD는 2026-09-16 Cloud SQL 이관과 함께 폐기했다 — 옛 mysql 컨테이너의
  # root 비밀번호였고, 앱은 AT_CLOUDSQL_APP_PASSWORD를 쓴다.
  app_secrets = toset([
    "AT_KIWOOM_APP_KEY",
    "AT_KIWOOM_APP_SECRET",
    "AT_KIWOOM_ACCOUNT_NO",
    "AT_REAL_KIS_APP_KEY", # KIS 해외주식 거래대금순위 조회
    "AT_REAL_KIS_APP_SECRET",
    "AT_REAL_TOSS_CLIENT_ID", # 토스 Market Indicators (지수·투자자 매매대금)
    "AT_REAL_TOSS_CLIENT_SECRET",
    "AT_CLOUDFLARE_API_TOKEN", # Caddy ACME DNS-01 챌린지 (Zone:DNS:Edit, judozoo.com 한정)
    "AT_GRAFANA_ADMIN_PASSWORD",
    "AT_GOOGLE_CLIENT_ID", # 구글 OAuth (웹 애플리케이션)
    "AT_GOOGLE_CLIENT_SECRET",
    "AT_SESSION_SECRET",         # 서명 쿠키 키. 바뀌면 전원 재로그인
    "AT_GRAFANA_MYSQL_PASSWORD", # Grafana가 app_user를 읽는 전용 계정 (SELECT만)
    "AT_CLOUDSQL_APP_PASSWORD",  # Cloud SQL 앱 계정(judozoo_app). 값은 terraform이 생성해 넣는다
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
  display_name = "judozoo GitHub Actions deployer SA"
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
  display_name                       = "judozoo GitHub OIDC"

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

# ---------------------------------------------------------------------------
# Cloud SQL for MySQL — private IP 전용
#
# 앱 계정 비밀번호는 terraform이 만들어 Secret Manager에 넣는다. 기존
# AT_DB_PASSWORD를 재사용하지 않는 이유는, 그 값이 지금 돌고 있는 mysql 컨테이너의
# root 비밀번호이고 실제 비밀번호는 볼륨 안에 박혀 있어서다 — 값을 바꾸면 이관 전에
# 앱이 먼저 기존 DB에 못 붙는다.
#
# special = false 는 멋이 아니다. settings.py가 접속 URL을
#   mysql+pymysql://{user}:{password}@{host}:{port}/{db}
# 로 **인코딩 없이** 조립하므로, 비밀번호에 / @ : # ? 가 들어가면 URL이 깨진다.
# ---------------------------------------------------------------------------
resource "random_password" "cloudsql_app" {
  length  = 32
  special = false
}

resource "google_secret_manager_secret_version" "cloudsql_app" {
  secret      = google_secret_manager_secret.app["AT_CLOUDSQL_APP_PASSWORD"].id
  secret_data = random_password.cloudsql_app.result
}

# Grafana 계정은 기존 시크릿(44자)을 그대로 쓴다. 새 인스턴스라 충돌하지 않는다.
data "google_secret_manager_secret_version" "grafana_mysql" {
  secret = "AT_GRAFANA_MYSQL_PASSWORD"
}

resource "google_sql_database_instance" "db" {
  name = "judozoo-db-prod"
  # **8.4가 아니라 8.0이다.** 8.4는 기본 에디션이 ENTERPRISE_PLUS로 잡히고, 그 에디션은
  # 공유코어 티어를 거부한다(db-perf-optimized-N-* 만 허용 — 월 $200대). 8.0은
  # ENTERPRISE라 db-g1-small을 쓴다. 소스는 mysql:8.4지만 스키마가 평범한 DDL이라
  # 덤프를 8.0으로 복원하는 데 문제가 없다.
  database_version = "MYSQL_8_0"
  region           = var.region

  # terraform 쪽 안전장치. 아래 settings.deletion_protection_enabled 와는 별개다 —
  # 하나는 terraform이, 하나는 API가 막는다. 삭제한 인스턴스 이름은 한동안 재사용도 못 한다.
  deletion_protection = true

  settings {
    # **명시해야 한다.** MySQL 8.4는 기본이 ENTERPRISE_PLUS로 잡히고, 그 에디션은
    # 공유코어 티어를 거부한다(db-perf-optimized-N-* 만 허용 — 월 $200대).
    edition                     = "ENTERPRISE"
    tier                        = "db-g1-small"
    availability_type           = "ZONAL"
    disk_type                   = "PD_SSD"
    disk_size                   = 10
    disk_autoresize             = true
    deletion_protection_enabled = true

    ip_configuration {
      ipv4_enabled    = false
      private_network = data.google_compute_network.default.id
    }

    backup_configuration {
      enabled = true
      # **이게 없으면 PITR이 없다.** 자동 백업만으로는 초 단위로 되감지 못한다 —
      # 이 이관의 핵심 명분이 사라진다.
      binary_log_enabled             = true
      start_time                     = "18:00" # UTC. = 03:00 KST, 현행 cron과 같은 시각
      transaction_log_retention_days = 7

      backup_retention_settings {
        retained_backups = 7
      }
    }

    # **UTC 기준이다.** day 1(월)~7(일), hour 0~23.
    # 토 19:00 UTC = 일 04:00 KST. KST로 적으면 장중에 재시작을 맞는다.
    maintenance_window {
      day          = 6
      hour         = 19
      update_track = "stable"
    }

    # 기존 컬럼이 시간대 없는 DATETIME에 KST 벽시계를 담고 있다.
    # 기본값(UTC)으로 두면 DB가 찍는 시각이 9시간 어긋난다.
    database_flags {
      name  = "default_time_zone"
      value = "+09:00"
    }
  }

  depends_on = [google_service_networking_connection.sql]
}

resource "google_sql_database" "trading" {
  name      = "trading"
  instance  = google_sql_database_instance.db.name
  charset   = "utf8mb4"
  collation = "utf8mb4_0900_ai_ci"
}

# 앱 전용 계정. root를 쓰지 않는다 — GRANT 범위는 §5에서 좁힌다.
resource "google_sql_user" "app" {
  name     = "judozoo_app"
  instance = google_sql_database_instance.db.name
  host     = "%"
  password = random_password.cloudsql_app.result
}

resource "google_sql_user" "grafana" {
  name     = "grafana"
  instance = google_sql_database_instance.db.name
  host     = "%"
  password = data.google_secret_manager_secret_version.grafana_mysql.secret_data
}
