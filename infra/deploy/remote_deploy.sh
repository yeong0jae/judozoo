#!/usr/bin/env bash
# VM에서 실행되는 배포 스크립트. GitHub Actions가 홈 디렉토리로 scp 후 ssh로 호출.
# 사용: bash ~/remote_deploy.sh <IMAGE_TAG> <AR_REPO> <REGION> [<SPRING_PROFILES_ACTIVE>]
#
# 전제:
#  - VM 인스턴스 SA가 secretmanager.secretAccessor + artifactregistry.reader 보유
#  - 호출 SSH 주체가 passwordless sudo 가능 (deploy SA = roles/compute.osAdminLogin)
set -euo pipefail

IMAGE_TAG="${1:?IMAGE_TAG required}"
AR_REPO="${2:?AR_REPO required}"
REGION="${3:?REGION required}"
SPRING_PROFILES_ACTIVE="${4:-kis-real}"   # GHA matrix가 인스턴스별로 주입 (kis-real | kiwoom-real)

cd "$HOME"
mkdir -p backend

# Secret Manager에서 KIS/KIWOOM/DB 시크릿 → backend/.env (VM 인스턴스 SA 권한 사용).
# AT_ 접두사는 같은 GCP project를 trading 인프라와 공유하기 때문에 충돌 회피용.
fetch() {
  gcloud secrets versions access latest --secret="$1"
}

DB_PASSWORD_VALUE="$(fetch AT_DB_PASSWORD)"

umask 077
cat > backend/.env <<EOF
DB_PASSWORD=${DB_PASSWORD_VALUE}
MYSQL_ROOT_PASSWORD=${DB_PASSWORD_VALUE}    # docker-compose.yml mysql 서비스가 이 변수명을 읽음
KIS_HTS_ID=$(fetch AT_KIS_HTS_ID)
REAL_KIS_APP_KEY=$(fetch AT_REAL_KIS_APP_KEY)
REAL_KIS_APP_SECRET=$(fetch AT_REAL_KIS_APP_SECRET)
REAL_KIS_ACCOUNT_NO=$(fetch AT_REAL_KIS_ACCOUNT_NO)
REAL_KIS_ACCOUNT_PRODUCT_CODE=$(fetch AT_REAL_KIS_ACCOUNT_PRODUCT_CODE)
REAL_KIWOOM_APP_KEY=$(fetch AT_KIWOOM_APP_KEY)
REAL_KIWOOM_APP_SECRET=$(fetch AT_KIWOOM_APP_SECRET)
REAL_KIWOOM_ACCOUNT_NO=$(fetch AT_KIWOOM_ACCOUNT_NO)
EOF

# Artifact Registry pull 인증: VM 인스턴스 SA(metadata)로 토큰 발급 → docker login.
# 모든 docker 명령을 동일하게 root(HOME=/root)로 실행해야 login 자격증명을
# compose가 같은 config.json에서 읽음. -E(HOME 보존) 쓰지 말 것.
gcloud auth print-access-token \
  | sudo docker login -u oauth2accesstoken --password-stdin "https://${REGION}-docker.pkg.dev"

COMPOSE_ENV=(
  "BACKEND_IMAGE=${AR_REPO}/backend"
  "FRONTEND_IMAGE=${AR_REPO}/frontend"
  "IMAGE_TAG=${IMAGE_TAG}"
  "SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE}"
)

sudo env "${COMPOSE_ENV[@]}" docker compose -f docker-compose.yml -f docker-compose.prod.yml pull
# deploy.yml의 `sudo rm -rf ~/observability` + 재-scp가 호스트 디렉토리의 inode를 교체하므로,
# 옵저버빌리티 컨테이너를 강제 재생성해 stale bind-mount(기존 inode를 가리킨 채 빈 dir로 보이는 현상)를 회피한다.
sudo env "${COMPOSE_ENV[@]}" docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --force-recreate --no-deps loki alloy grafana
sudo env "${COMPOSE_ENV[@]}" docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d
sudo docker image prune -f
