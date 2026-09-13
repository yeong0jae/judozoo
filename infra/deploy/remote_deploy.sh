#!/usr/bin/env bash
# VM에서 실행되는 배포 스크립트. GitHub Actions가 홈 디렉토리로 scp 후 ssh로 호출.
# 사용: bash ~/remote_deploy.sh <IMAGE_TAG> <AR_REPO> <REGION>
#
# 전제:
#  - VM 인스턴스 SA가 secretmanager.secretAccessor + artifactregistry.reader 보유
#  - 호출 SSH 주체가 passwordless sudo 가능 (deploy SA = roles/compute.osAdminLogin)
set -euo pipefail

IMAGE_TAG="${1:?IMAGE_TAG required}"
AR_REPO="${2:?AR_REPO required}"
REGION="${3:?REGION required}"

DOMAIN="judozoo.com"
GRAFANA_DOMAIN="grafana.${DOMAIN}"

cd "$HOME"
# mysql·backend가 둘 다 ./secrets/.env 를 읽는다.
mkdir -p secrets

# Secret Manager에서 KIS/KIWOOM/DB 시크릿 → secrets/.env (VM 인스턴스 SA 권한 사용).
# AT_ 접두사는 같은 GCP project를 trading 인프라와 공유하기 때문에 충돌 회피용.
fetch() {
  gcloud secrets versions access latest --secret="$1"
}

DB_PASSWORD_VALUE="$(fetch AT_DB_PASSWORD)"

umask 077
cat > secrets/.env <<EOF
DB_PASSWORD=${DB_PASSWORD_VALUE}
MYSQL_ROOT_PASSWORD=${DB_PASSWORD_VALUE}    # docker-compose.yml mysql 서비스가 이 변수명을 읽음
REAL_KIS_APP_KEY=$(fetch AT_REAL_KIS_APP_KEY)
REAL_KIS_APP_SECRET=$(fetch AT_REAL_KIS_APP_SECRET)
REAL_KIWOOM_APP_KEY=$(fetch AT_KIWOOM_APP_KEY)
REAL_KIWOOM_APP_SECRET=$(fetch AT_KIWOOM_APP_SECRET)
REAL_KIWOOM_ACCOUNT_NO=$(fetch AT_KIWOOM_ACCOUNT_NO)
REAL_TOSS_CLIENT_ID=$(fetch AT_REAL_TOSS_CLIENT_ID)
REAL_TOSS_CLIENT_SECRET=$(fetch AT_REAL_TOSS_CLIENT_SECRET)
GF_SECURITY_ADMIN_PASSWORD=$(fetch AT_GRAFANA_ADMIN_PASSWORD)
CLOUDFLARE_API_TOKEN=$(fetch AT_CLOUDFLARE_API_TOKEN)
DOMAIN=${DOMAIN}
GRAFANA_DOMAIN=${GRAFANA_DOMAIN}
GOOGLE_CLIENT_ID=$(fetch AT_GOOGLE_CLIENT_ID)
GOOGLE_CLIENT_SECRET=$(fetch AT_GOOGLE_CLIENT_SECRET)
SESSION_SECRET=$(fetch AT_SESSION_SECRET)
EOF

# Artifact Registry pull 인증: VM 인스턴스 SA(metadata)로 토큰 발급 → docker login.
# 모든 docker 명령을 동일하게 root(HOME=/root)로 실행해야 login 자격증명을
# compose가 같은 config.json에서 읽음. -E(HOME 보존) 쓰지 말 것.
gcloud auth print-access-token \
  | sudo docker login -u oauth2accesstoken --password-stdin "https://${REGION}-docker.pkg.dev"

COMPOSE_ENV=(
  "FRONTEND_IMAGE=${AR_REPO}/frontend"
  "BACKEND_IMAGE=${AR_REPO}/backend"
  "CADDY_IMAGE=${AR_REPO}/caddy"
  "IMAGE_TAG=${IMAGE_TAG}"
)

# 옛 배포 이미지가 누적돼 새 이미지 pull이 디스크 부족으로 실패하는 것 방지.
# 현재 컨테이너가 쓰는 이미지는 보존되고, 안 쓰는(이전 태그) 이미지만 제거된다.
sudo docker image prune -af

sudo env "${COMPOSE_ENV[@]}" docker compose -f docker-compose.yml -f docker-compose.prod.yml pull
# deploy.yml의 `sudo rm -rf ~/observability` + 재-scp가 호스트 디렉토리의 inode를 교체하므로,
# 옵저버빌리티 컨테이너를 강제 재생성해 stale bind-mount(기존 inode를 가리킨 채 빈 dir로 보이는 현상)를 회피한다.
sudo env "${COMPOSE_ENV[@]}" docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --force-recreate --no-deps loki alloy grafana
# --remove-orphans: compose 파일에서 서비스를 지워도 **이미 떠 있는 컨테이너는 남는다**.
# Kotlin 백엔드를 제거했을 때 실제로 고아 컨테이너로 계속 돌아 폴러가 중복 적재됐다.
sudo env "${COMPOSE_ENV[@]}" docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --remove-orphans
# 교체로 막 쓰임이 끝난 직전 배포 이미지까지 정리(-a) — 디스크 누적 방지.
sudo docker image prune -af

# 헬스체크 — IAP SSH 터널을 별도로 한 번 더 열지 않도록 배포와 같은 세션에서 검사.

# 1) Python 백엔드. 죽어 있으면 nginx가 이관된 경로(/api/news/)에 502를 준다.
#    프론트만 검사하면 이 실패가 배포 성공으로 묻힌다.
for i in $(seq 1 18); do
  status="$(sudo docker inspect --format '{{.State.Health.Status}}' \
    "$(sudo env "${COMPOSE_ENV[@]}" docker compose -f docker-compose.yml -f docker-compose.prod.yml ps -q backend)" 2>/dev/null || true)"
  if [ "$status" = "healthy" ]; then echo "backend health OK ($i)"; break; fi
  if [ "$i" = "18" ]; then echo "backend health check failed (status=${status:-unknown})"; exit 1; fi
  echo "backend not ready ($status), retry $i"; sleep 5
done

# 2) 프론트 — Caddy를 거쳐 검사한다. 호스트에 3000을 더 이상 퍼블리시하지 않으므로
#    localhost:3000은 쓸 수 없다. --resolve로 DNS를 우회해 루프백의 Caddy를 때리면
#    TLS 인증서(SNI·유효기간)까지 한 번에 검증된다.
for i in $(seq 1 24); do
  if curl -fsS --resolve "${DOMAIN}:443:127.0.0.1" "https://${DOMAIN}/" >/dev/null; then
    echo "health OK ($i)"; exit 0
  fi
  echo "not ready, retry $i"; sleep 10
done
echo "health check failed"; exit 1
