# DB 복원 절차

운영 MySQL을 백업에서 되살리는 방법. **이 문서의 명령은 2026-09-13에 실제로 실행해 검증했다.**

## 백업은 어디에 있나

```
gs://trading-496508-auto-trading-db-backup/mysql/trading-YYYY-MM-DD-HHMM.sql.gz
```

- 매일 **03:00 KST** cron이 적재한다 (`/etc/cron.d/judozoo-db-backup`, VM에서 root로 실행)
- **7일 보관.** lifecycle이 지난 것을 자동 삭제한다
- 로그: VM의 `/var/log/judozoo-backup.log`
- 크기: 압축 약 7~8MB (원본 약 42MB)

## 백업 목록 보기

```bash
gcloud storage ls -l gs://trading-496508-auto-trading-db-backup/mysql/
```

## 복원

### 1. 덤프 받기

```bash
gcloud storage cp gs://trading-496508-auto-trading-db-backup/mysql/trading-<시각>.sql.gz .
```

### 2. 먼저 로컬에서 확인한다

운영에 바로 밀어넣지 말 것. 깨끗한 컨테이너에 넣어 행 수부터 본다.

```bash
docker run -d --rm --name restorecheck -e MYSQL_ROOT_PASSWORD=test -e MYSQL_DATABASE=trading mysql:8.4
# 준비될 때까지 대기
until docker exec restorecheck mysqladmin ping -uroot -ptest --silent; do sleep 3; done

gunzip -c trading-<시각>.sql.gz | docker exec -i restorecheck mysql -uroot -ptest trading

docker exec restorecheck mysql -uroot -ptest trading -e \
  'SELECT COUNT(*) FROM app_user; SELECT COUNT(*) FROM index_minute_candle;'

docker rm -f restorecheck
```

### 3. 운영에 반영

```bash
gcloud compute ssh auto-trading-app-kiwoom-real --tunnel-through-iap --zone asia-northeast3-a

# VM 안에서
gcloud storage cp gs://trading-496508-auto-trading-db-backup/mysql/trading-<시각>.sql.gz /tmp/
CID=$(sudo docker ps -qf name=mysql)
gunzip -c /tmp/trading-<시각>.sql.gz | \
  sudo docker exec -i "$CID" sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" trading'
```

> **덤프는 `DROP TABLE IF EXISTS`를 포함한다.** 복원하면 해당 테이블의 현재 내용이 사라진다.
> 부분 복원이 필요하면 덤프에서 해당 테이블 구간만 잘라 쓴다.

백엔드를 재시작해 캐시를 비운다:

```bash
sudo docker restart "$CID"
sudo docker restart $(sudo docker ps -qf name=backend)
```

## 백업이 안 돌 때

```bash
# cron 등록 확인
sudo cat /etc/cron.d/judozoo-db-backup

# 수동 실행
SCRIPT=$(sudo grep -o "/home/[^ ]*backup_db\.sh" /etc/cron.d/judozoo-db-backup)
sudo bash "$SCRIPT" trading-496508-auto-trading-db-backup

# 로그
sudo tail -50 /var/log/judozoo-backup.log
```

cron 파일은 배포마다 `remote_deploy.sh`가 다시 쓴다. 사라졌다면 배포를 한 번 돌리면 된다.

## 알려진 제약

- **복구 지점이 하루 단위다.** 03:00 백업 이후 장중에 유실되면 그날 데이터는 못 살린다.
  분봉·시그널은 재수집이 불가능하니, 더 촘촘히 필요하면 주기를 줄여야 한다.
- **7일이 지나면 없다.** 문제를 일주일 안에 발견해야 한다.
- 백업은 **같은 GCP 프로젝트** 안에 있다. 프로젝트 단위 사고에는 대비가 안 된다.
