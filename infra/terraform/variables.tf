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
  default     = "yeong0jae/judozoo"
}

variable "allowed_web_source_ranges" {
  description = "웹(tcp:80,443) 인바운드를 허용할 소스 CIDR. SSH는 이 값을 쓰지 않는다(IAP 대역 고정)"
  type        = list(string)

  # **Cloudflare 엣지 대역만 받는다.** 도메인이 Cloudflare 프록시(오렌지 구름) 뒤에 있으므로
  # 정상 트래픽은 전부 여기서 온다. 오리진 IP를 알아낸 쪽이 프록시를 건너뛰고 직접 붙는 길을
  # 막는 것이 목적이다 — 프록시만 켜고 이 목록을 좁히지 않으면 아무것도 막히지 않는다.
  #
  # **프록시가 꺼져 있으면 사이트가 통째로 막힌다.** 프록시를 먼저 켜고 이 값을 적용할 것.
  #
  # 출처: https://www.cloudflare.com/ips-v4 (2026-09-17 기준 15개).
  # Cloudflare가 대역을 늘리면 갱신해야 한다 — 안 하면 일부 방문자가 막힌다.
  # 갱신: curl -s https://www.cloudflare.com/ips-v4
  #
  # IPv6는 넣지 않는다. 오리진에 AAAA 레코드가 없어 Cloudflare가 IPv4로만 붙는다.
  default = [
    "173.245.48.0/20",
    "103.21.244.0/22",
    "103.22.200.0/22",
    "103.31.4.0/22",
    "141.101.64.0/18",
    "108.162.192.0/18",
    "190.93.240.0/20",
    "188.114.96.0/20",
    "197.234.240.0/22",
    "198.41.128.0/17",
    "162.158.0.0/15",
    "104.16.0.0/13",
    "104.24.0.0/14",
    "172.64.0.0/13",
    "131.0.72.0/22",
  ]
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

variable "db_backup_retention_days" {
  description = "DB 덤프 보관 일수. 지나면 lifecycle이 자동 삭제한다"
  type        = number
  default     = 7
}
