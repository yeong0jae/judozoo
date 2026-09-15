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
GOOGLE_CLIENT_ID=$(fetch AT_GOOGLE_CLIENT_ID)
GOOGLE_CLIENT_SECRET=$(fetch AT_GOOGLE_CLIENT_SECRET)
SESSION_SECRET=$(fetch AT_SESSION_SECRET)
GF_MYSQL_PASSWORD=$(fetch AT_GRAFANA_MYSQL_PASSWORD)
EOF

# Grafana용 읽기 전용 MySQL 계정. 가입자 패널이 app_user를 읽는다.
# 매 배포마다 다시 적용하므로 멱등하고, 비밀번호가 바뀌어도 따라간다.
# SELECT 권한을 app_user 한 테이블로 제한한다 — 관측 도구에 DB 전체를 열어줄 이유가 없다.
MYSQL_CID="$(sudo docker ps -qf name=mysql)"
if [ -n "$MYSQL_CID" ]; then
  GF_PW="$(grep '^GF_MYSQL_PASSWORD=' secrets/.env | cut -d= -f2-)"
  sudo docker exec -e GF_PW="$GF_PW" "$MYSQL_CID" sh -c '
    mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
      CREATE USER IF NOT EXISTS \"grafana\"@\"%\" IDENTIFIED BY \"$GF_PW\";
      ALTER USER \"grafana\"@\"%\" IDENTIFIED BY \"$GF_PW\";
      GRANT SELECT ON trading.app_user TO \"grafana\"@\"%\";
      FLUSH PRIVILEGES;"' 2>/dev/null && echo "grafana MySQL 계정 준비됨"
fi

# DB 백업 cron. 배포마다 덮어쓰므로 멱등하다.
# root로 돌린다 — cron에는 TTY가 없어 sudo가 걸릴 수 있다.
# 03:00 KST — 20:00 애프터마켓 캡처가 끝나고 다음 장 시작 전, 폴러가 조용한 시간.
# 스크립트는 deploy.yml이 홈으로 scp한다.
BACKUP_BUCKET="${PROJECT_ID:-trading-496508}-auto-trading-db-backup"
sudo tee /etc/cron.d/judozoo-db-backup >/dev/null <<CRON
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
CRON_TZ=Asia/Seoul
0 3 * * * root bash $HOME/backup_db.sh ${BACKUP_BUCKET} >> /var/log/judozoo-backup.log 2>&1
CRON
sudo chmod 0644 /etc/cron.d/judozoo-db-backup

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

# 2) 프론트 — Caddy를 거쳐 검사한다. 프론트는 호스트에 포트를 퍼블리시하지 않으므로
#    직접 때릴 주소가 없다(호스트의 3000은 Grafana다 — 프론트의 3000은 컨테이너 내부 포트다).
#    --resolve로 DNS를 우회해 루프백의 Caddy를 때리면
#    TLS 인증서(SNI·유효기간)까지 한 번에 검증된다.
for i in $(seq 1 24); do
  if curl -fsS --resolve "${DOMAIN}:443:127.0.0.1" "https://${DOMAIN}/" >/dev/null; then
    echo "health OK ($i)"; exit 0
  fi
  echo "not ready, retry $i"; sleep 10
done
echo "health check failed"; exit 1
