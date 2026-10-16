#!/usr/bin/env bash
set -euo pipefail

LOKI_CONFIG_HASH="${1:?LOKI_CONFIG_HASH required}"
PROMETHEUS_CONFIG_HASH="${2:?PROMETHEUS_CONFIG_HASH required}"
TEMPO_CONFIG_HASH="${3:?TEMPO_CONFIG_HASH required}"
GRAFANA_CONFIG_HASH="${4:?GRAFANA_CONFIG_HASH required}"

cd "$HOME"
mkdir -p secrets

fetch() {
  gcloud secrets versions access latest --secret="$1"
}

SLACK_WEBHOOK_URL="$(fetch AT_GRAFANA_SLACK_WEBHOOK_URL || true)"
if [ -z "$SLACK_WEBHOOK_URL" ]; then
  echo "AT_GRAFANA_SLACK_WEBHOOK_URL 을 읽지 못했다 — Slack 알림이 조용히 죽는다."
  echo "  gcloud secrets create AT_GRAFANA_SLACK_WEBHOOK_URL --data-file=- <<< '<웹훅 URL>'"
  exit 1
fi

SIGNUP_SLACK_WEBHOOK_URL="$(fetch AT_GRAFANA_SLACK_SIGNUP_WEBHOOK_URL || true)"
if [ -z "$SIGNUP_SLACK_WEBHOOK_URL" ]; then
  echo "AT_GRAFANA_SLACK_SIGNUP_WEBHOOK_URL 이 없다 — 가입 알림만 발송되지 않는다(배포는 계속)."
fi

umask 077
cat > secrets/.env <<EOF
GF_SECURITY_ADMIN_PASSWORD=$(fetch AT_GRAFANA_ADMIN_PASSWORD)
GF_MYSQL_PASSWORD=$(fetch AT_GRAFANA_MYSQL_PASSWORD)
SLACK_WEBHOOK_URL=${SLACK_WEBHOOK_URL}
SIGNUP_SLACK_WEBHOOK_URL=${SIGNUP_SLACK_WEBHOOK_URL}
DB_HOST=10.100.0.3
EOF

COMPOSE=(-f docker-compose.ops.yml -f docker-compose.ops.prod.yml)

HASHES=(
  "LOKI_CONFIG_HASH=${LOKI_CONFIG_HASH}"
  "PROMETHEUS_CONFIG_HASH=${PROMETHEUS_CONFIG_HASH}"
  "TEMPO_CONFIG_HASH=${TEMPO_CONFIG_HASH}"
  "GRAFANA_CONFIG_HASH=${GRAFANA_CONFIG_HASH}"
)

sudo env "${HASHES[@]}" docker compose "${COMPOSE[@]}" up -d --remove-orphans

sudo docker image prune -af

for i in $(seq 1 24); do
  loki_ok=0; graf_ok=0; prom_ok=0; tempo_ok=0
  curl -fsS "http://localhost:3100/ready" >/dev/null 2>&1 && loki_ok=1
  curl -fsS "http://localhost:3000/api/health" >/dev/null 2>&1 && graf_ok=1
  curl -fsS "http://localhost:9090/-/ready" >/dev/null 2>&1 && prom_ok=1
  curl -fsS "http://localhost:3200/ready" >/dev/null 2>&1 && tempo_ok=1
  if [ "$loki_ok" = 1 ] && [ "$graf_ok" = 1 ] && [ "$prom_ok" = 1 ] && [ "$tempo_ok" = 1 ]; then
    echo "ops health OK ($i) — loki/prometheus/tempo ready, grafana healthy"; healthy=1; break
  fi
  echo "not ready (loki=$loki_ok grafana=$graf_ok prometheus=$prom_ok tempo=$tempo_ok), retry $i"; sleep 5
done
if [ "${healthy:-0}" != 1 ]; then
  echo "ops health check failed (loki=$loki_ok grafana=$graf_ok prometheus=$prom_ok tempo=$tempo_ok)"
  sudo env "${HASHES[@]}" docker compose "${COMPOSE[@]}" ps || true
  for svc in loki grafana prometheus tempo; do
    echo "----- $svc -----"
    sudo env "${HASHES[@]}" docker compose "${COMPOSE[@]}" logs --tail 40 "$svc" || true
  done
  exit 1
fi

for f in /etc/grafana/provisioning/datasources/prometheus.yaml \
         /etc/grafana/provisioning/datasources/loki.yaml \
         /etc/grafana/provisioning/dashboards/dashboards.yaml \
         /etc/grafana/provisioning/alerting/rules.yaml \
         /etc/grafana/provisioning/alerting/contact-points.yaml \
         /etc/grafana/provisioning/datasources/tempo.yaml \
         /var/lib/grafana/dashboards/metrics.json; do
  if ! sudo env "${HASHES[@]}" docker compose "${COMPOSE[@]}" exec -T grafana test -f "$f"; then
    echo "grafana 마운트 깨짐: $f 가 컨테이너 안에 없다"; exit 1
  fi
done
echo "grafana 프로비저닝 마운트 OK"
