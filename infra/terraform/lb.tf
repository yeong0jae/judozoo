resource "google_project_service" "certificatemanager" {
  service            = "certificatemanager.googleapis.com"
  disable_on_destroy = false
}

resource "google_compute_global_address" "lb" {
  name = "judozoo-lb-ip"

  depends_on = [google_project_service.apis]
}

resource "google_compute_instance_group" "app" {
  name      = "judozoo-app"
  zone      = var.zone
  instances = [for vm in google_compute_instance.app : vm.self_link]

  named_port {
    name = "http"
    port = 3000
  }
}

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
  timeout_sec           = 30
  health_checks         = [google_compute_health_check.frontend.id]

  custom_request_headers = ["X-Client-IP: {client_ip_address}"]

  enable_cdn       = true
  compression_mode = "AUTOMATIC"
  cdn_policy {
    cache_mode = "USE_ORIGIN_HEADERS"

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

resource "google_certificate_manager_dns_authorization" "site" {
  name   = "judozoo-site-pp"
  domain = var.domain
  type   = "PER_PROJECT_RECORD"

  lifecycle {
    create_before_destroy = true
  }

  depends_on = [google_project_service.certificatemanager]
}

resource "google_certificate_manager_certificate" "site" {
  name = "judozoo-site-v4"

  managed {
    domains            = [var.domain]
    dns_authorizations = [google_certificate_manager_dns_authorization.site.id]
  }

  lifecycle {
    create_before_destroy = true
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
