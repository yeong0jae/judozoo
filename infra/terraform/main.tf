resource "google_project_service" "apis" {
  for_each = toset([
    "compute.googleapis.com",
    "artifactregistry.googleapis.com",
    "secretmanager.googleapis.com",
    "iam.googleapis.com",
    "iamcredentials.googleapis.com",
    "sts.googleapis.com",
    "storage.googleapis.com",
    "sqladmin.googleapis.com",
    "servicenetworking.googleapis.com",
  ])
  service            = each.value
  disable_on_destroy = false
}

resource "google_project_service" "aiplatform" {
  service            = "aiplatform.googleapis.com"
  disable_on_destroy = false
}

locals {
  instances = {
    "kiwoom-real" = {
      name_suffix = "-kiwoom-real"
      vm_name     = "judozoo-server-prod"
    }
  }
}

resource "google_compute_address" "frontend" {
  for_each = local.instances
  name     = "auto-trading-frontend-ip${each.value.name_suffix}"
  region   = var.region

  depends_on = [google_project_service.apis]
}

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

resource "google_project_iam_member" "vm_aiplatform_user" {
  project = var.project_id
  role    = "roles/aiplatform.user"
  member  = "serviceAccount:${google_service_account.vm.email}"
}

resource "google_compute_firewall" "ssh" {
  name    = "auto-trading-allow-ssh"
  network = "default"

  allow {
    protocol = "tcp"
    ports    = ["22"]
  }

  source_ranges = ["REDACTED_IP/20"]
  target_tags   = ["auto-trading"]

  depends_on = [google_project_service.apis]
}

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

  log_config {
    enable = true
    filter = "ERRORS_ONLY"
  }
}

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

resource "google_compute_firewall" "ops_prometheus" {
  name    = "judozoo-ops-allow-prom-write"
  network = "default"

  allow {
    protocol = "tcp"
    ports    = ["9090"]
  }

  source_tags = ["auto-trading"]
  target_tags = ["judozoo-ops"]

  depends_on = [google_project_service.apis]
}

resource "google_compute_firewall" "ops_otlp" {
  name    = "judozoo-ops-allow-otlp"
  network = "default"

  allow {
    protocol = "tcp"
    ports    = ["4317"]
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
    ports    = ["22", "3000"]
  }

  source_ranges = ["REDACTED_IP/20"]
  target_tags   = ["judozoo-ops"]

  depends_on = [google_project_service.apis]
}

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

  deletion_policy = "ABANDON"
}

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

resource "google_compute_instance" "ops" {
  name         = "judozoo-ops-prod"
  machine_type = "e2-medium"
  zone         = var.zone

  allow_stopping_for_update = true

  tags = ["judozoo-ops"]

  boot_disk {
    initialize_params {
      image = "ubuntu-os-cloud/ubuntu-2404-lts-amd64"
      size  = var.boot_disk_size_gb
    }
  }

  network_interface {
    network = "default"
  }

  service_account {
    email  = google_service_account.vm.email
    scopes = ["cloud-platform"]
  }

  metadata = {
    enable-oslogin = "TRUE"
    startup-script = file("${path.module}/startup.sh")
  }

  depends_on = [google_compute_router_nat.nat]
}

resource "google_artifact_registry_repository" "docker" {
  location      = var.region
  repository_id = "auto-trading"
  format        = "DOCKER"

  cleanup_policies {
    id     = "delete-all"
    action = "DELETE"
    condition {
      tag_state = "ANY"
    }
  }

  cleanup_policies {
    id     = "keep-recent-3"
    action = "KEEP"
    most_recent_versions {
      keep_count = 3
    }
  }

  depends_on = [google_project_service.apis]
}

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

resource "google_storage_bucket_iam_member" "vm_backup_writer" {
  bucket = google_storage_bucket.db_backup.name
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${google_service_account.vm.email}"
}

locals {
  app_secrets = toset([
    "AT_KIWOOM_APP_KEY",
    "AT_KIWOOM_APP_SECRET",
    "AT_KIWOOM_ACCOUNT_NO",
    "AT_REAL_KIS_APP_KEY",
    "AT_REAL_KIS_APP_SECRET",
    "AT_REAL_TOSS_CLIENT_ID",
    "AT_REAL_TOSS_CLIENT_SECRET",
    "AT_GRAFANA_ADMIN_PASSWORD",
    "AT_GOOGLE_CLIENT_ID",
    "AT_GOOGLE_CLIENT_SECRET",
    "AT_SESSION_SECRET",
    "AT_GRAFANA_MYSQL_PASSWORD",
    "AT_CLOUDSQL_APP_PASSWORD",
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

resource "google_service_account" "deployer" {
  account_id   = "auto-trading-gha-deployer"
  display_name = "judozoo GitHub Actions deployer SA"
}

resource "google_secret_manager_secret_iam_member" "deployer_slack_webhook" {
  secret_id = "AT_SLACK_DEPLOY_WEBHOOK_URL"
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.deployer.email}"
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

resource "random_password" "cloudsql_app" {
  length  = 32
  special = false
}

resource "google_secret_manager_secret_version" "cloudsql_app" {
  secret      = google_secret_manager_secret.app["AT_CLOUDSQL_APP_PASSWORD"].id
  secret_data = random_password.cloudsql_app.result
}

data "google_secret_manager_secret_version" "grafana_mysql" {
  secret = "AT_GRAFANA_MYSQL_PASSWORD"
}

resource "google_sql_database_instance" "db" {
  name             = "judozoo-db-prod"
  database_version = "MYSQL_8_0"
  region           = var.region

  deletion_protection = true

  settings {
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
      enabled                        = true
      binary_log_enabled             = true
      start_time                     = "18:00"
      transaction_log_retention_days = 7

      backup_retention_settings {
        retained_backups = 7
      }
    }

    maintenance_window {
      day          = 6
      hour         = 19
      update_track = "stable"
    }

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
