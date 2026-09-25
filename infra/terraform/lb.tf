# ---------------------------------------------------------------------------
# 전역 외부 애플리케이션 부하 분산기 (023)
#
# 한국 방문자가 Cloudflare LA 지점을 거쳐 태평양을 왕복하던 입구를 대신한다.
# 구글 서울 진입점에서 구글 망으로 들어와 VM까지 내부망으로 간다.
#
# **기존 입구(Cloudflare → Caddy)와 나란히 선다.** DNS를 넘기기 전까지 이 경로로는
# 아무도 오지 않는다. 전환·정리 순서는 docs/tasks/023 참고.
#
# TLS는 여기서 끝난다. 부하 분산기 → VM은 구글 망 안의 HTTP이고, VM의 nginx가
# 호스트 3000을 직접 연다(Caddy를 거치지 않는다).
# ---------------------------------------------------------------------------

# `google_project_service.apis` 목록에 넣지 않는다. 그 리소스에 변경이 걸리면 거기 의존하는
# `data.google_compute_network.default` 읽기가 apply 시점으로 밀리고, 네트워크 id가 미정이 되어
# **Cloud SQL 인스턴스·피어링·NAT가 교체 대상으로 잡힌다**(2026-09-25 plan에서 실제로 나왔다).
resource "google_project_service" "certificatemanager" {
  service            = "certificatemanager.googleapis.com"
  disable_on_destroy = false
}

resource "google_compute_global_address" "lb" {
  name = "judozoo-lb-ip"

  depends_on = [google_project_service.apis]
}

# ---------------------------------------------------------------------------
# 백엔드: 기존 앱 VM을 비관리형 인스턴스 그룹으로 묶는다
# ---------------------------------------------------------------------------
resource "google_compute_instance_group" "app" {
  name      = "judozoo-app"
  zone      = var.zone
  instances = [for vm in google_compute_instance.app : vm.self_link]

  named_port {
    name = "http"
    port = 3000
  }
}

# nginx `location = /healthz` — `/`를 쓰면 프로버 여러 대의 요청이 액세스 로그(Loki)에 쌓인다.
resource "google_compute_health_check" "frontend" {
  name = "judozoo-frontend"

  http_health_check {
    port         = 3000
    request_path = "/healthz"
  }
}

resource "google_compute_backend_service" "frontend" {
  name                  = "judozoo-frontend"
  load_balancing_scheme = "EXTERNAL_MANAGED"
  protocol              = "HTTP"
  port_name             = "http"
  # 브로커 호출이 공유 리미터에서 최대 20초 기다린다 — 기본값 30초 안에 든다.
  timeout_sec   = 30
  health_checks = [google_compute_health_check.frontend.id]

  # 실제 방문자 IP를 단일 값으로 넘긴다. XFF는 `클라이언트, 부하분산기IP` 모양이라
  # 부하 분산기 IP까지 nginx 신뢰 목록에 넣어야 한다 — CF-Connecting-IP와 같은 방식을 택했다.
  custom_request_headers = ["X-Client-IP: {client_ip_address}"]

  # 캐시 여부는 nginx의 Cache-Control이 정한다. CACHE_ALL_STATIC은 확장자로 추측해서
  # 022에서 robots.txt 옛 버전이 엣지에 박힌 사고를 다시 부른다.
  enable_cdn = true
  # Cloudflare가 해주던 brotli·gzip을 여기서 한다. nginx에는 gzip 설정이 없어서, 이게 없으면
  # JS 673KB가 압축 없이 나간다(Cloudflare 경유 시 br 207KB).
  compression_mode = "AUTOMATIC"
  cdn_policy {
    cache_mode = "USE_ORIGIN_HEADERS"

    # 기본 캐시 키 그대로. 프로바이더가 이 블록이나 signed_url 설정 중 하나를 요구한다.
    cache_key_policy {
      include_host         = true
      include_protocol     = true
      include_query_string = true
    }
  }

  backend {
    group           = google_compute_instance_group.app.self_link
    balancing_mode  = "UTILIZATION"
    capacity_scaler = 1.0
  }
}

# ---------------------------------------------------------------------------
# 인증서 — Certificate Manager, DNS 인증
#
# DNS 인증은 DNS가 부하 분산기를 가리키기 **전에** 발급받게 해준다. 로드밸런서 인증
# 방식(google_compute_managed_ssl_certificate)은 DNS가 이미 이쪽을 가리켜야 발급돼서
# 전환 순간 TLS가 빈다.
#
# **인증 CNAME은 terraform 밖(Cloudflare DNS)에 있다.** 값은 output `lb_dns_authorization`.
# 지우면 인증서 갱신이 실패한다.
# ---------------------------------------------------------------------------
resource "google_certificate_manager_dns_authorization" "site" {
  name   = "judozoo-site"
  domain = var.domain

  depends_on = [google_project_service.certificatemanager]
}

resource "google_certificate_manager_certificate" "site" {
  name = "judozoo-site"

  managed {
    domains            = [var.domain]
    dns_authorizations = [google_certificate_manager_dns_authorization.site.id]
  }
}

resource "google_certificate_manager_certificate_map" "site" {
  name = "judozoo-site"

  depends_on = [google_project_service.certificatemanager]
}

resource "google_certificate_manager_certificate_map_entry" "site" {
  name         = "judozoo-site"
  map          = google_certificate_manager_certificate_map.site.name
  hostname     = var.domain
  certificates = [google_certificate_manager_certificate.site.id]
}

# ---------------------------------------------------------------------------
# HTTPS 입구
# ---------------------------------------------------------------------------
resource "google_compute_url_map" "https" {
  name            = "judozoo-https"
  default_service = google_compute_backend_service.frontend.id
}

resource "google_compute_target_https_proxy" "site" {
  name            = "judozoo-https"
  url_map         = google_compute_url_map.https.id
  certificate_map = "//certificatemanager.googleapis.com/${google_certificate_manager_certificate_map.site.id}"
}

resource "google_compute_global_forwarding_rule" "https" {
  name                  = "judozoo-https"
  load_balancing_scheme = "EXTERNAL_MANAGED"
  ip_address            = google_compute_global_address.lb.id
  port_range            = "443"
  target                = google_compute_target_https_proxy.site.id
}

# ---------------------------------------------------------------------------
# HTTP → HTTPS 리다이렉트 — Caddy가 자동으로 하던 일
# ---------------------------------------------------------------------------
resource "google_compute_url_map" "http_redirect" {
  name = "judozoo-http-redirect"

  default_url_redirect {
    https_redirect         = true
    redirect_response_code = "MOVED_PERMANENTLY_DEFAULT"
    strip_query            = false
  }
}

resource "google_compute_target_http_proxy" "redirect" {
  name    = "judozoo-http-redirect"
  url_map = google_compute_url_map.http_redirect.id
}

resource "google_compute_global_forwarding_rule" "http" {
  name                  = "judozoo-http"
  load_balancing_scheme = "EXTERNAL_MANAGED"
  ip_address            = google_compute_global_address.lb.id
  port_range            = "80"
  target                = google_compute_target_http_proxy.redirect.id
}

# ---------------------------------------------------------------------------
# 방화벽: 부하 분산기 프록시·상태 확인 대역 → nginx 3000
#
# 이 두 대역 밖에서는 3000에 닿지 못한다 — VM IP를 알아도 부하 분산기를 건너뛸 길이 없다.
# 기존 `web` 규칙(Cloudflare 대역 → 80/443)은 전환 후 1주, 되돌리기용으로 남겨둔다.
# ---------------------------------------------------------------------------
resource "google_compute_firewall" "lb" {
  name    = "judozoo-allow-lb"
  network = "default"

  allow {
    protocol = "tcp"
    ports    = ["3000"]
  }

  source_ranges = ["130.211.0.0/22", "35.191.0.0/16"]
  target_tags   = ["auto-trading"]

  depends_on = [google_project_service.apis]
}
