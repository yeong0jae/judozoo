#!/usr/bin/env bash
# 통합 테스트용 스키마(`src/test/resources/schema.sql`)를 Alembic에서 다시 뜬다.
#
# 공존하는 동안 스키마 주인은 Alembic이다(028). Kotlin 테스트는 이 파일로 테이블을 만들고
# JPA는 `validate`로 맞는지만 본다. **Alembic 마이그레이션을 더하면 이걸 돌려 함께 커밋한다** —
# 안 돌리면 테스트는 옛 스키마로 통과하고, 운영 기동에서 validate가 터진다.
#
# 빈 MySQL을 잠깐 띄워 `alembic upgrade head`를 돌리고 `mysqldump --no-data`로 뜬다.
# 기준선 마이그레이션이 DB를 들여다봐서 `alembic --sql`(오프라인)로는 뽑히지 않는다.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/backend-kotlin/src/test/resources/schema.sql"
mkdir -p "$(dirname "$OUT")"
NAME="judozoo-schema-dump-$$"
PORT=33099

docker run -d --rm --name "$NAME" -p "$PORT:3306" -e MYSQL_ROOT_PASSWORD=pw -e MYSQL_DATABASE=trading mysql:8.4 >/dev/null
trap 'docker rm -f "$NAME" >/dev/null' EXIT
until docker exec "$NAME" mysql -uroot -ppw -e 'SELECT 1' trading >/dev/null 2>&1; do sleep 1; done

(cd "$ROOT/backend" && DB_HOST=127.0.0.1 DB_PORT=$PORT DB_USERNAME=root DB_PASSWORD=pw DB_NAME=trading uv run alembic upgrade head)

{
  echo "-- scripts/dump-schema.sh가 만든다. 손으로 고치지 않는다."
  docker exec "$NAME" mysqldump -uroot -ppw --no-data --skip-comments --skip-add-drop-table --skip-set-charset trading 2>/dev/null \
    | grep -v '^/\*!' | sed -E 's/ AUTO_INCREMENT=[0-9]+//'
} > "$OUT"
echo "wrote $OUT ($(grep -c 'CREATE TABLE' "$OUT") tables)"
