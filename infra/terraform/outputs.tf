output "vm_external_ips" {
  description = "프론트 접속용 고정 외부 IP — 인스턴스 키별"
  value       = { for k, addr in google_compute_address.frontend : k => addr.address }
}

output "vm_names" {
  description = "VM 인스턴스명 — 인스턴스 키별 (GitHub Variables VM_NAME_* 값)"
  value       = { for k, vm in google_compute_instance.app : k => vm.name }
}

output "vm_zones" {
  description = "VM zone — 인스턴스 키별 (GitHub Variables VM_ZONE_* 값)"
  value       = { for k, vm in google_compute_instance.app : k => vm.zone }
}

output "artifact_registry_repo" {
  description = "이미지 push/pull 경로 접두사 (GitHub Variables AR_REPO 값)"
  value       = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.docker.repository_id}"
}

output "wif_provider" {
  description = "GitHub Actions auth용 WIF provider 리소스명 (Variables WIF_PROVIDER 값)"
  value       = google_iam_workload_identity_pool_provider.github.name
}

output "deployer_sa_email" {
  description = "GitHub Actions가 가장할 배포 SA (Variables DEPLOY_SA 값)"
  value       = google_service_account.deployer.email
}

output "db_backup_bucket" {
  description = "DB 덤프 버킷 (remote_deploy.sh가 여기로 올린다)"
  value       = google_storage_bucket.db_backup.name
}

output "sql_private_ip" {
  description = "Cloud SQL private IP (remote_deploy.sh의 DB_HOST 값)"
  value       = google_sql_database_instance.db.private_ip_address
}

output "ops_vm_name" {
  description = "관측 VM 이름 (GitHub Variables VM_NAME_OPS 값)"
  value       = google_compute_instance.ops.name
}

output "ops_internal_dns" {
  description = "앱 VM의 alloy가 로그를 밀어 넣을 주소"
  value       = "${google_compute_instance.ops.name}.${google_compute_instance.ops.zone}.c.${var.project_id}.internal"
}

output "lb_ip" {
  description = "부하 분산기 전역 IP — 전환 시 Cloudflare A 레코드 값 (023)"
  value       = google_compute_global_address.lb.address
}

output "lb_dns_authorization" {
  description = "인증서 DNS 인증용 레코드 — Cloudflare DNS에 넣는다(회색 구름) (023)"
  value       = google_certificate_manager_dns_authorization.site.dns_resource_record
}
