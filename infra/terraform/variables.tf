variable "project_id" {
  description = "GCP 프로젝트 ID (trading-496508을 재사용; 리소스는 auto-trading- 프리픽스로 분리)"
  type        = string
  default     = "trading-496508"
}

variable "region" {
  description = "리소스 생성 리전"
  type        = string
  default     = "asia-northeast3"
}

variable "zone" {
  description = "VM 존"
  type        = string
  default     = "asia-northeast3-a"
}

variable "github_repository" {
  description = "WIF에 허용할 GitHub 저장소 (owner/repo)"
  type        = string
  default     = "yeong0jae/trading-desk"
}

variable "allowed_web_source_ranges" {
  description = "프론트(tcp:3000) 인바운드를 허용할 소스 CIDR. 개인용 기본 전체개방, 필요시 본인 IP/32로 축소"
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "machine_type" {
  description = "VM 머신 타입"
  type        = string
  default     = "e2-medium"
}

variable "boot_disk_size_gb" {
  description = "VM 부트 디스크 크기(GB) — MySQL 데이터 볼륨도 부트 디스크에 위치"
  type        = number
  default     = 20
}
