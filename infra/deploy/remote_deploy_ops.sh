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

umask 077
cat > secrets/.env <<EOF
GF_SECURITY_ADMIN_PASSWORD=$(fetch AT_GRAFANA_ADMIN_PASSWORD)
GF_MYSQL_PASSWORD=$(fetch AT_GRAFANA_MYSQL_PASSWORD)
DB_HOST=10.100.0.3
EOF

# DB_HOST는 Cloud SQL private IP. Grafana의 가입자 패널이 이 값을 읽는다
# (provisioning/datasources/mysql.yaml 의 `url: $DB_HOST:3306`).
# ops VM도 같은 VPC라 피어링 경로가 그대로 열린다.

COMPOSE=(-f docker-compose.ops.yml -f docker-compose.ops.prod.yml)

# --remove-orphans: compose 파일에서 서비스를 지워도 이미 떠 있는 컨테이너는 남는다.
sudo docker compose "${COMPOSE[@]}" up -d --remove-orphans

# 이미지가 바뀌면 옛 것이 디스크에 쌓인다. 부트디스크 20GB뿐이다.
sudo docker image prune -af

# 헬스체크 — 배포와 같은 SSH 세션에서 끝낸다.
for i in $(seq 1 24); do
  loki_ok=0; graf_ok=0; prom_ok=0
  curl -fsS "http://localhost:3100/ready" >/dev/null 2>&1 && loki_ok=1
  curl -fsS "http://localhost:3000/api/health" >/dev/null 2>&1 && graf_ok=1
  curl -fsS "http://localhost:9090/-/ready" >/dev/null 2>&1 && prom_ok=1
  if [ "$loki_ok" = 1 ] && [ "$graf_ok" = 1 ] && [ "$prom_ok" = 1 ]; then
    echo "ops health OK ($i) — loki ready, grafana healthy, prometheus ready"; exit 0
  fi
  echo "not ready (loki=$loki_ok grafana=$graf_ok prometheus=$prom_ok), retry $i"; sleep 5
done
echo "ops health check failed"; exit 1
