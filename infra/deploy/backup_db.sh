#!/usr/bin/env bash
# MySQL 덤프를 GCS로 올린다. VM의 cron이 하루 한 번 호출한다.
# 사용: bash ~/backup_db.sh <BUCKET>
#
# 누적 데이터(분봉·투자자 수급·시그널·테마 스냅샷)는 과거 시점이라 브로커에서 다시 받을 수 없다.
# 그래서 VM 디스크가 아니라 별도 저장소에 둔다.
set -euo pipefail

BUCKET="${1:?BUCKET required}"
STAMP="$(date +%Y-%m-%d-%H%M)"
DEST="gs://${BUCKET}/mysql/trading-${STAMP}.sql.gz"

CID="$(sudo docker ps -qf name=mysql)"
if [ -z "$CID" ]; then
  echo "mysql 컨테이너가 없다 — 백업 건너뜀"
  exit 1
fi

# --single-transaction: InnoDB에서 락 없이 일관된 시점을 뜬다. 앱이 계속 돌아도 된다.
# --routines/--events: 지금은 없지만 생기면 같이 담긴다.
# 비밀번호는 컨테이너 환경변수를 그대로 참조한다 — 명령줄에 노출하지 않는다.
sudo docker exec "$CID" sh -c \
  'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --events --no-tablespaces trading' \
  | gzip -9 \
  | gcloud storage cp - "$DEST"

SIZE="$(gcloud storage ls -l "$DEST" | awk 'NR==1{print $1}')"
echo "백업 완료 ${DEST} (${SIZE} bytes)"
