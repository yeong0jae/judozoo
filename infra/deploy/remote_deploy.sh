#!/usr/bin/env bash
# VM에서 실행되는 배포 스크립트. GitHub Actions가 홈 디렉토리로 scp 후 ssh로 호출.
# 사용: bash ~/remote_deploy.sh <AR_REPO> <REGION> <BACKEND_TAG> <FRONTEND_TAG> <ALLOY_CONFIG_HASH>
#
# **태그가 서비스마다 다르다.** 각 디렉터리의 git 트리 해시라서 그 서비스 내용이
# 바뀔 때만 값이 변한다. 안 바뀐 서비스는 태그가 같아 compose가 건드리지 않는다 —
# 예전에는 커밋 SHA 하나를 셋이 공유해서, 프론트 문구만 고쳐도 장중에 백엔드가 재시작됐다.
#
# 전제:
#  - VM 인스턴스 SA가 secretmanager.secretAccessor + artifactregistry.reader 보유
#  - 호출 SSH 주체가 passwordless sudo 가능 (deploy SA = roles/compute.osAdminLogin)
set -euo pipefail

AR_REPO="${1:?AR_REPO required}"
REGION="${2:?REGION required}"
BACKEND_TAG="${3:?BACKEND_TAG required}"
FRONTEND_TAG="${4:?FRONTEND_TAG required}"
ALLOY_CONFIG_HASH="${5:?ALLOY_CONFIG_HASH required}"

cd "$HOME"
# backend가 ./secrets/.env 를 읽는다.
mkdir -p secrets

# Secret Manager에서 KIS/KIWOOM/DB 시크릿 → secrets/.env (VM 인스턴스 SA 권한 사용).
# AT_ 접두사는 같은 GCP project를 trading 인프라와 공유하기 때문에 충돌 회피용.
fetch() {
  gcloud secrets versions access latest --secret="$1"
}

# Cloud SQL 앱 계정. 기존 AT_DB_PASSWORD는 옛 mysql 컨테이너의 root 비밀번호라
# 재사용하지 않는다 (이관 검증이 끝나면 폐기).
DB_PASSWORD_VALUE="$(fetch AT_CLOUDSQL_APP_PASSWORD)"

# DB_HOST는 Cloud SQL private IP. `terraform output sql_private_ip`가 정답이며,
# 인스턴스를 재생성하지 않는 한 바뀌지 않는다.


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

# DB 백업은 Cloud SQL 자동 백업 + PITR이 맡는다. VM cron은 걷어냈다 —
# 남은 /etc/cron.d/judozoo-db-backup 이 있으면 손으로 지워야 한다(배포는 안 지운다).

# Artifact Registry pull 인증: VM 인스턴스 SA(metadata)로 토큰 발급 → docker login.
# 모든 docker 명령을 동일하게 root(HOME=/root)로 실행해야 login 자격증명을
# compose가 같은 config.json에서 읽음. -E(HOME 보존) 쓰지 말 것.
gcloud auth print-access-token \
  | sudo docker login -u oauth2accesstoken --password-stdin "https://${REGION}-docker.pkg.dev"

COMPOSE_ENV=(
  "FRONTEND_IMAGE=${AR_REPO}/frontend"
  "BACKEND_IMAGE=${AR_REPO}/backend"
  "BACKEND_TAG=${BACKEND_TAG}"
  "FRONTEND_TAG=${FRONTEND_TAG}"
  "ALLOY_CONFIG_HASH=${ALLOY_CONFIG_HASH}"
)

# 옛 배포 이미지가 누적돼 새 이미지 pull이 디스크 부족으로 실패하는 것 방지.
# 현재 컨테이너가 쓰는 이미지는 보존되고, 안 쓰는(이전 태그) 이미지만 제거된다.
sudo docker image prune -af

sudo env "${COMPOSE_ENV[@]}" docker compose -f docker-compose.yml -f docker-compose.prod.yml pull
# **alloy를 무조건 재생성하지 않는다.** 예전에는 deploy.yml이 `rm -rf ~/observability` 후
# 재-scp해서 inode가 교체됐고, 재사용된 컨테이너가 사라진 inode를 가리켜 설정이 빈 것처럼
# 보였다. 지금은 rm 대신 소유권만 고쳐 inode를 보존하고, config.alloy가 실제로 바뀔 때만
# ALLOY_CONFIG_HASH 라벨이 달라져 compose가 그때만 재생성한다.
# loki·grafana·prometheus·tempo는 ops VM으로 떠났다 — remote_deploy_ops.sh가 맡는다.
# --remove-orphans: compose 파일에서 서비스를 지워도 **이미 떠 있는 컨테이너는 남는다**.
# Kotlin 백엔드를 제거했을 때 실제로 고아 컨테이너로 계속 돌아 폴러가 중복 적재됐다.
sudo env "${COMPOSE_ENV[@]}" docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --remove-orphans
# 교체로 막 쓰임이 끝난 직전 배포 이미지까지 정리(-a) — 디스크 누적 방지.
sudo docker image prune -af

# 헬스체크 — IAP SSH 터널을 별도로 한 번 더 열지 않도록 배포와 같은 세션에서 검사.

# 1) Python 백엔드. 죽어 있으면 nginx가 /api/ 요청에 502를 준다.
#    프론트만 검사하면 이 실패가 배포 성공으로 묻힌다.
for i in $(seq 1 18); do
  status="$(sudo docker inspect --format '{{.State.Health.Status}}' \
    "$(sudo env "${COMPOSE_ENV[@]}" docker compose -f docker-compose.yml -f docker-compose.prod.yml ps -q backend)" 2>/dev/null || true)"
  if [ "$status" = "healthy" ]; then echo "backend health OK ($i)"; break; fi
  if [ "$i" = "18" ]; then echo "backend health check failed (status=${status:-unknown})"; exit 1; fi
  echo "backend not ready ($status), retry $i"; sleep 5
done

# 2) alloy — 메트릭 exporter(unix·cadvisor)가 호스트 마운트를 읽는다. 마운트가 하나라도
#    없으면 alloy는 설정 평가 단계에서 **통째로 죽는다**(실측: exit 1). 메트릭만 비는 게
#    아니라 **로그 수집까지 같이 멈추는데**, 나머지 검사는 전부 초록으로 통과한다.
#    평가에 5초쯤 걸리므로 한 번 보고 끝내지 않고 여러 번 확인한다.
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

# 3) 프론트 — 부하 분산기가 붙는 호스트 3000을 그대로 때린다(023). TLS는 부하 분산기가
#    끝내므로 여기서 볼 인증서가 없다. `/healthz`가 아니라 `/`를 쳐서 index.html까지 확인한다.
for i in $(seq 1 24); do
  if curl -fsS "http://127.0.0.1:3000/" >/dev/null; then
    echo "health OK ($i)"; exit 0
  fi
  echo "not ready, retry $i"; sleep 10
done
echo "health check failed"; exit 1
