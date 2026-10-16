#!/usr/bin/env bash
set -euo pipefail

AR_REPO="${1:?AR_REPO required}"
REGION="${2:?REGION required}"
BACKEND_TAG="${3:?BACKEND_TAG required}"
FRONTEND_TAG="${4:?FRONTEND_TAG required}"
ALLOY_CONFIG_HASH="${5:?ALLOY_CONFIG_HASH required}"

cd "$HOME"
mkdir -p secrets

fetch() {
  gcloud secrets versions access latest --secret="$1"
}

DB_PASSWORD_VALUE="$(fetch AT_CLOUDSQL_APP_PASSWORD)"

umask 077
cat > secrets/.env <<EOF
DB_PASSWORD=${DB_PASSWORD_VALUE}
DB_HOST=10.100.0.3
DB_PORT=3306
DB_USERNAME=judozoo_app
REAL_KIS_APP_KEY=$(fetch AT_REAL_KIS_APP_KEY)
REAL_KIS_APP_SECRET=$(fetch AT_REAL_KIS_APP_SECRET)
REAL_KIWOOM_APP_KEY=$(fetch AT_KIWOOM_APP_KEY)
REAL_KIWOOM_APP_SECRET=$(fetch AT_KIWOOM_APP_SECRET)
REAL_KIWOOM_ACCOUNT_NO=$(fetch AT_KIWOOM_ACCOUNT_NO)
REAL_TOSS_CLIENT_ID=$(fetch AT_REAL_TOSS_CLIENT_ID)
REAL_TOSS_CLIENT_SECRET=$(fetch AT_REAL_TOSS_CLIENT_SECRET)
GOOGLE_CLIENT_ID=$(fetch AT_GOOGLE_CLIENT_ID)
GOOGLE_CLIENT_SECRET=$(fetch AT_GOOGLE_CLIENT_SECRET)
SESSION_SECRET=$(fetch AT_SESSION_SECRET)
EOF

gcloud auth print-access-token \
  | sudo docker login -u oauth2accesstoken --password-stdin "https://${REGION}-docker.pkg.dev"

COMPOSE_ENV=(
  "FRONTEND_IMAGE=${AR_REPO}/frontend"
  "BACKEND_IMAGE=${AR_REPO}/backend"
  "BACKEND_TAG=${BACKEND_TAG}"
  "FRONTEND_TAG=${FRONTEND_TAG}"
  "ALLOY_CONFIG_HASH=${ALLOY_CONFIG_HASH}"
)

sudo docker image prune -af

sudo env "${COMPOSE_ENV[@]}" docker compose -f docker-compose.yml -f docker-compose.prod.yml pull
sudo env "${COMPOSE_ENV[@]}" docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --remove-orphans
sudo docker image prune -af

for i in $(seq 1 24); do
  status="$(sudo docker inspect --format '{{.State.Health.Status}}' \
    "$(sudo env "${COMPOSE_ENV[@]}" docker compose -f docker-compose.yml -f docker-compose.prod.yml ps -q backend)" 2>/dev/null || true)"
  if [ "$status" = "healthy" ]; then echo "backend health OK ($i)"; break; fi
  if [ "$i" = "24" ]; then echo "backend health check failed (status=${status:-unknown})"; exit 1; fi
  echo "backend not ready ($status), retry $i"; sleep 5
done

for i in 1 2 3; do
  sleep 5
  state="$(sudo docker inspect --format '{{.State.Status}}' \
    "$(sudo env "${COMPOSE_ENV[@]}" docker compose -f docker-compose.yml -f docker-compose.prod.yml ps -q alloy)" 2>/dev/null || true)"
  if [ "$state" != "running" ]; then
    echo "alloy not running (status=${state:-unknown}) — 설정·마운트를 확인한다"
    sudo env "${COMPOSE_ENV[@]}" docker compose -f docker-compose.yml -f docker-compose.prod.yml logs --tail 30 alloy || true
    exit 1
  fi
done
echo "alloy OK"

for i in $(seq 1 24); do
  if curl -fsS "http://127.0.0.1:3000/" >/dev/null; then
    echo "health OK ($i)"; exit 0
  fi
  echo "not ready, retry $i"; sleep 10
done
echo "health check failed"; exit 1
