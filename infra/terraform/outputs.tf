output "vm_external_ip" {
  description = "프론트 접속용 고정 외부 IP"
  value       = google_compute_address.frontend.address
}

output "vm_name" {
  value = google_compute_instance.app.name
}

output "vm_zone" {
  value = google_compute_instance.app.zone
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
