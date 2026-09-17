#!/usr/bin/env bash
# 관측 VM(judozoo-ops-prod)에서 실행되는 배포 스크립트.
# GitHub Actions가 홈 디렉토리로 scp 후 ssh로 호출한다. 사용: bash ~/remote_deploy_ops.sh
#
# 앱 VM의 remote_deploy.sh와 달리 이미지 빌드·pull이 없다 — loki·grafana 모두
# 공개 이미지라 Artifact Registry를 거치지 않는다.
set -euo pipefail

cd "$HOME"
# grafana가 ./secrets/.env 를 읽는다 (loki는 시크릿이 없다).
mkdir -p secrets

fetch() {
  gcloud secrets versions access latest --secret="$1"
}

# Slack 웹훅은 **먼저 변수로 받아 비었는지 본다.** 히어독 안에서 $(fetch ...)가 실패하면
# set -e가 안 걸리고 빈 값으로 넘어간다 — 그러면 Grafana 수신처 URL이 비고, 알림이
# 발송되지 않는데 배포도 알림도 아무 소리를 내지 않는다. 이 스택에서 이미 두 번 겪은
# 실패 방식이라(020 함정 참고) 여기서 끊는다.
SLACK_WEBHOOK_URL="$(fetch AT_GRAFANA_SLACK_WEBHOOK_URL || true)"
if [ -z "$SLACK_WEBHOOK_URL" ]; then
  echo "AT_GRAFANA_SLACK_WEBHOOK_URL 을 읽지 못했다 — Slack 알림이 조용히 죽는다."
  echo "  gcloud secrets create AT_GRAFANA_SLACK_WEBHOOK_URL --data-file=- <<< '<웹훅 URL>'"
  exit 1
fi

umask 077
cat > secrets/.env <<EOF
GF_SECURITY_ADMIN_PASSWORD=$(fetch AT_GRAFANA_ADMIN_PASSWORD)
GF_MYSQL_PASSWORD=$(fetch AT_GRAFANA_MYSQL_PASSWORD)
SLACK_WEBHOOK_URL=${SLACK_WEBHOOK_URL}
DB_HOST=10.100.0.3
EOF

# DB_HOST는 Cloud SQL private IP. Grafana의 가입자 패널이 이 값을 읽는다
# (provisioning/datasources/mysql.yaml 의 `url: $DB_HOST:3306`).
# ops VM도 같은 VPC라 피어링 경로가 그대로 열린다.

COMPOSE=(-f docker-compose.ops.yml -f docker-compose.ops.prod.yml)

# --force-recreate가 필요하다. deploy.yml이 매 배포마다 `sudo rm -rf ~/observability` 후
# 재-scp하므로 호스트 디렉토리의 **inode가 교체된다**. 설정이 그대로면 compose는 컨테이너를
# 재사용하는데, 그 컨테이너는 사라진 inode를 계속 가리켜 마운트가 빈 디렉토리로 보인다.
#
# **증상이 조용하다.** grafana는 정상 기동하고 헬스체크도 통과하며, 프로비저닝만 실패한다 —
# 새 데이터소스·대시보드가 영영 안 올라온다. 이미 DB에 들어간 것들은 그대로 보이므로
# 화면만 봐서는 눈치채기 어렵다. 020에서 Prometheus 데이터소스가 이래서 누락됐다.
# 앱 VM의 remote_deploy.sh가 alloy에 같은 조치를 하고 있다.
#
# --remove-orphans: compose 파일에서 서비스를 지워도 이미 떠 있는 컨테이너는 남는다.
sudo docker compose "${COMPOSE[@]}" up -d --force-recreate --remove-orphans

# 이미지가 바뀌면 옛 것이 디스크에 쌓인다. 부트디스크 20GB뿐이다.
sudo docker image prune -af

# 헬스체크 — 배포와 같은 SSH 세션에서 끝낸다.
for i in $(seq 1 24); do
  loki_ok=0; graf_ok=0; prom_ok=0
  curl -fsS "http://localhost:3100/ready" >/dev/null 2>&1 && loki_ok=1
  curl -fsS "http://localhost:3000/api/health" >/dev/null 2>&1 && graf_ok=1
  curl -fsS "http://localhost:9090/-/ready" >/dev/null 2>&1 && prom_ok=1
  if [ "$loki_ok" = 1 ] && [ "$graf_ok" = 1 ] && [ "$prom_ok" = 1 ]; then
    echo "ops health OK ($i) — loki ready, grafana healthy, prometheus ready"; healthy=1; break
  fi
  echo "not ready (loki=$loki_ok grafana=$graf_ok prometheus=$prom_ok), retry $i"; sleep 5
done
if [ "${healthy:-0}" != 1 ]; then
  # **왜 안 떴는지 남긴다.** 이 줄만 있고 끝나면 다음에 같은 일이 나도 조사할 게 없다 —
  # 컨테이너는 이미 교체돼 직전 로그가 사라진 뒤다(2026-09-17 grafana 2분 타임아웃 때 겪었다).
  echo "ops health check failed (loki=$loki_ok grafana=$graf_ok prometheus=$prom_ok)"
  sudo docker compose "${COMPOSE[@]}" ps || true
  for svc in loki grafana prometheus; do
    echo "----- $svc -----"
    sudo docker compose "${COMPOSE[@]}" logs --tail 40 "$svc" || true
  done
  exit 1
fi

# 프로비저닝 마운트 검사. **헬스체크가 통과한 뒤에 본다** — 기동 중에 exec하면 오탐이 난다.
# 위 stale bind-mount를 직접 잡는 장치다. 컨테이너 안에서 파일이 보이는지만 확인하므로
# Grafana 비밀번호가 필요 없다. 이 검사가 없으면 프로비저닝 실패가 조용히 지나간다 —
# grafana는 멀쩡히 뜨고 /api/health도 ok를 주기 때문이다.
for f in /etc/grafana/provisioning/datasources/prometheus.yaml \
         /etc/grafana/provisioning/datasources/loki.yaml \
         /etc/grafana/provisioning/dashboards/dashboards.yaml \
         /etc/grafana/provisioning/alerting/rules.yaml \
         /etc/grafana/provisioning/alerting/contact-points.yaml \
         /var/lib/grafana/dashboards/metrics.json; do
  if ! sudo docker compose "${COMPOSE[@]}" exec -T grafana test -f "$f"; then
    echo "grafana 마운트 깨짐: $f 가 컨테이너 안에 없다"; exit 1
  fi
done
echo "grafana 프로비저닝 마운트 OK"

