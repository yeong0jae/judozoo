# 018 — MySQL을 Cloud SQL(private IP)로 이관

Goal: VM 안 `mysql:8.4` 컨테이너를 **Cloud SQL for MySQL(private IP 전용)** 로 옮긴다. 공인 IP를 열지 않고 VPC 피어링으로만 붙으며, 논리적 손상을 되감을 수 있는 **PITR**을 얻는다. 앱 VM은 destroy 0건으로 보존한다.

## 왜 Cloud SQL인가

세 안(현행 유지 / 전용 DB VM / Cloud SQL)을 장애 시나리오로 비교한 결과다.

| 시나리오 | 현행 | 전용 DB VM | **Cloud SQL** |
|---|---|---|---|
| 부트 디스크 가득 참 | 전체 장애 | 격리 | 자동 증가 |
| 인스턴스 손실 | 데이터 증발 | DB 무사 | 무관 |
| **논리적 손상** (잘못된 DELETE·마이그레이션) | 24h 손실 | **24h 손실** | **초 단위 되감기** |
| 패치·업그레이드 | 수동 | 수동 | 자동 |
| 새 방화벽 표면 | 없음 | **tcp:3306 신규** | 없음(피어링 전용) |

**전용 DB VM은 어느 칸에서도 1등이 아니다.** 같은 돈을 쓰면서 RPO 24시간을 그대로 두고 방화벽 표면만 늘린다. `default-allow-internal`이 VPC 내부 전 포트를 열어둔 상태라 태그 기반 3306 규칙이 실제 경계가 되지도 못한다([security.md](../../.claude/rules/security.md) 4항).

**논리적 손상 칸을 채우는 것은 Cloud SQL뿐이다.** 분봉·수급은 브로커에서 재취득이 불가능하므로 이 칸의 무게가 다른 칸보다 크다.

## 진입 조건 (2026-09-15 실측)

| 항목 | 값 |
|---|---|
| DB 실 데이터 | **29.5MB** (`mysql-data` 볼륨은 650.6MB) |
| 최대 테이블 | `index_minute_candle` 7.5MB / 41,572행 |
| mysql 컨테이너 메모리 | 603.8MB |
| VM | `auto-trading-app-kiwoom-real` e2-medium, 컨테이너 7개, available 2,247MB |
| 부트디스크 | 19GB 중 9.4GB (52%) |
| 서브넷 `default` | `10.178.0.0/20` (asia-northeast3), auto-mode |
| 현재 백업 | `backup_db.sh` → GCS, 03:00 KST, 7일 보관, **RPO 최대 24시간** |
| 앱 DB 계정 | **`root`** (`docker-compose.yml:34`) |

## 설계 결정

| 항목 | 선택 | 이유 |
|---|---|---|
| 제품 | **Cloud SQL for MySQL** | PITR. 나머지 안은 논리적 손상을 못 되감는다 |
| 버전 | **`MYSQL_8_4`** | 현재 컨테이너와 같은 메이저. 덤프/복원 호환을 위해 맞춘다 |
| 티어 | **`db-g1-small`** | 실데이터 29.5MB, 컨테이너 실사용 604MB. 공유코어로 충분하고 필요하면 무중단에 가깝게 올릴 수 있다 |
| 연결 | **private IP 전용** (`ipv4_enabled = false`) | 공인 IP를 아예 만들지 않는다. 방화벽으로 막는 것보다 강하다 |
| 가용성 | **단일 존**(기본) | HA는 비용 2배. 존 장애는 현행도 못 버티므로 이번 범위가 아니다 |
| 앱 계정 | **`judozoo_app` 신설. root를 쓰지 않는다** | 지금 앱이 root로 붙는다. Grafana는 한 테이블로 좁혀놓고 앱만 전권인 건 앞뒤가 안 맞는다. 계정을 새로 만드는 이 시점이 아니면 영영 바꾸지 않는다 |
| 이름 | **`judozoo-db-prod`** | 새 리소스는 `judozoo-*`. `auto-trading-*`는 이름 변경 전 잔재이고 기존 자원은 개명이 곧 재생성이라 그대로 둔다 |
| 피어링 대역 | **`10.100.0.0/24`** | auto-mode 서브넷은 `10.128.0.0/9`에서 나온다. 그 바깥이어야 겹치지 않는다 |
| 사람 접속 | **IAP 터널 → 앱 VM → private IP** | 별도 베스천을 만들지 않는다. 이미 IAP를 쓰고 있다 |

### 목표 구성

```
        ┌─────────────────────────────────────────┐
        │ VPC default · asia-northeast3           │
        │                                         │
        │  ┌───────────────────────────────────┐  │
        │  │ 존 asia-northeast3-a              │  │
        │  │  앱 VM  e2-medium (기존 그대로)    │  │
        │  │   caddy / frontend / backend      │  │
        │  │   loki / alloy / grafana          │  │
        │  │   ※ mysql 컨테이너 제거            │  │
        │  └───────────────┬───────────────────┘  │
        │                  │ 3306 (private)       │
        │   google_compute_global_address         │
        │   10.100.0.0/24 · VPC_PEERING           │
        └──────────────────┼──────────────────────┘
                           │ VPC 피어링
        ┌──────────────────▼──────────────────────┐
        │ Google 관리 테넌트 프로젝트               │
        │  Cloud SQL  judozoo-db-prod             │
        │   MYSQL_8_4 · db-g1-small               │
        │   private IP only · 단일 존              │
        │   자동 백업 + PITR(binlog)               │
        └─────────────────────────────────────────┘

  사람 → IAP 터널 → 앱 VM → private IP:3306
```

### 비용 (개략 — 확정은 요금 계산기)

| 항목 | 월 추정 |
|---|---|
| Cloud SQL `db-g1-small` (단일 존) | ~$25 |
| 스토리지 10GB + 백업/binlog 보관 | ~$5 |
| 앱 VM (변동 없음) | ~$27 |
| **합계** | **~$57** |

전용 DB VM 안(~$44)보다 월 $10 남짓 비싸고, 그 차액으로 PITR·자동 패치·디스크 자동 증가를 산다.

---

## 1. 네트워크 — Private Service Access

> 이 구간이 틀리면 뒤가 전부 막힌다. 대역이 겹치면 피어링 자체가 실패한다.

- [ ] `main.tf` — `google_project_service`에 **`servicenetworking.googleapis.com`**, **`sqladmin.googleapis.com`** 추가
- [ ] `main.tf` — `google_compute_global_address` `judozoo-sql-peering`
      - `purpose = "VPC_PEERING"`, `address_type = "INTERNAL"`, `prefix_length = 24`, `address = "10.100.0.0"`
      - `network = "default"`
- [ ] `main.tf` — `google_service_networking_connection`
      - `service = "servicenetworking.googleapis.com"`
      - `reserved_peering_ranges = [google_compute_global_address.sql_peering.name]`
- [ ] `terraform apply` → 피어링 생성 확인
      ```
      gcloud compute networks peerings list --network=default
      ```

> **방화벽 규칙을 만들지 않는다.** 피어링 경로는 VPC 방화벽을 거치지 않는다. 전용 DB VM 안이었다면 여기서 `tcp:3306` 규칙이 필요했고, `default-allow-internal` 때문에 그 규칙이 실제 경계가 되지도 못했을 것이다.

## 2. Cloud SQL 인스턴스

- [ ] `main.tf` — `google_sql_database_instance` `judozoo-db-prod`
      - `database_version = "MYSQL_8_4"`, `region = var.region`, `tier = "db-g1-small"`
      - `availability_type = "ZONAL"`
      - `ip_configuration { ipv4_enabled = false, private_network = default VPC self_link }`
      - `disk_autoresize = true`, `disk_size = 10`, `disk_type = "PD_SSD"`
      - `backup_configuration`
        - `enabled = true`, **`binary_log_enabled = true`** ← 이게 없으면 PITR이 없다
        - `start_time = "18:00"` (UTC) = **03:00 KST** — 현행 cron과 같은 시각
        - `transaction_log_retention_days = 7`, `retained_backups = 7`
      - `maintenance_window { day = 6, hour = 19 }` (UTC) = **일요일 04:00 KST**
      - `database_flags { name = "default_time_zone", value = "+09:00" }`
      - `deletion_protection = true` (리소스 인자와 `settings.deletion_protection_enabled` **둘 다**)
      - `depends_on = [google_service_networking_connection...]`
- [ ] `main.tf` — `google_sql_database` `trading` (charset `utf8mb4`)
- [ ] `main.tf` — `google_sql_user` `judozoo_app` — 비밀번호는 `AT_DB_PASSWORD`
- [ ] `main.tf` — `google_sql_user` `grafana` — 비밀번호는 `AT_GRAFANA_MYSQL_PASSWORD`
- [ ] `outputs.tf` — `sql_private_ip` 출력
- [ ] `terraform apply` → 인스턴스 생성 (5~10분 걸린다)

> **유지보수 창을 UTC로 지정한다.** `day`는 1(월)~7(일), `hour`는 0~23 **UTC**다. 일요일 04:00 KST를 원하면 `day = 6`(토), `hour = 19`다. KST 기준으로 적으면 장중에 재시작을 맞는다 — 폴러가 10초 주기로 도는 시스템이라 치명적이다.

## 3. 데이터 이관

> 실데이터 29.5MB라 덤프·복원은 수십 초다. 다운타임은 앱 재배포 시간이 지배한다.
> 장 마감 후, 20:00 애프터마켓 캡처가 끝나고 03:00 백업 전인 **21:00~23:00** 창에 한다.

- [ ] (사전) 리허설 — 아래 전체를 한 번 돌려보고 행 수를 대조한다. **앱은 계속 기존 DB를 본다**
- [ ] 앱 정지 — `docker compose stop backend` (폴러가 더 쓰지 않도록)
- [ ] 덤프
      ```bash
      CID=$(sudo docker ps -qf name=mysql)
      sudo docker exec "$CID" sh -c \
        'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --events \
         --no-tablespaces --set-gtid-purged=OFF trading' > /tmp/final.sql
      ```
      `--set-gtid-purged=OFF`가 없으면 관리형 인스턴스에서 GTID 관련 구문으로 복원이 막힌다.
- [ ] 복원 — 앱 VM에서 private IP로 직접 밀어넣는다
      ```bash
      SQL_IP=<terraform output sql_private_ip>
      sudo docker run --rm -i --network host mysql:8.4 \
        mysql -h "$SQL_IP" -u judozoo_app -p"$DB_PASSWORD" trading < /tmp/final.sql
      ```
- [ ] **행 수 대조** — `index_minute_candle`, `market_investor_snapshot`, `futures_investor_snapshot`, `signal_event`, `stocks`, `app_user` 6개
- [ ] 덤프 파일 삭제 (`/tmp/final.sql` — 평문 데이터다)

## 4. 앱 전환

- [ ] `docker-compose.yml` — `mysql` 서비스 **제거**, `depends_on: mysql` 제거, `mysql-data` 볼륨 선언 제거
- [ ] `docker-compose.yml` — `DB_HOST: ${DB_HOST}`, `DB_PORT: ${DB_PORT}`, **`DB_USERNAME: ${DB_USERNAME}`**
- [ ] `remote_deploy.sh` — `secrets/.env`에 `DB_HOST`(private IP) · `DB_PORT=3306` · `DB_USERNAME=judozoo_app` 기록
- [ ] `remote_deploy.sh` — `MYSQL_ROOT_PASSWORD` 줄 제거 (mysql 컨테이너가 없다)
- [ ] 배포 → `/health/db` 200 확인
- [ ] 화면 육안 확인 — 주도주 후보 / 시황 / 실시간 로그

> **`settings.py`의 `DB_PORT` 기본값이 33061이다.** compose에서 3306을 명시 주입해야 한다.
> **private IP를 시크릿에 넣지 않는다.** 비밀이 아니고, `terraform output`이 정답을 갖고 있다.

## 5. Grafana 계정 재설계

현재 `remote_deploy.sh`가 `docker exec`로 mysql 컨테이너에 들어가 계정을 만들고 GRANT한다. **Cloud SQL에는 `docker exec`할 컨테이너가 없다.**

- [ ] 계정 생성은 **Terraform**(`google_sql_user.grafana`)이 맡는다
- [ ] **GRANT는 Terraform이 못 한다** — SQL 실행이라 별도 경로가 필요하다.
      `remote_deploy.sh`에서 mysql 클라이언트 컨테이너로 네트워크 너머에 실행한다(멱등):
      ```sql
      GRANT SELECT ON trading.app_user TO 'grafana'@'%';
      ```
- [ ] `remote_deploy.sh`의 기존 `docker ps -qf name=mysql` 블록 **삭제**
      (컨테이너가 없으면 조용히 건너뛴다 — **실패하지 않고 잘못 동작한다**)
- [ ] `observability/grafana/provisioning/datasources/mysql.yaml` — `url: mysql:3306` → `url: $DB_HOST:3306`
      Grafana는 프로비저닝 파일에서 `$VAR`를 전개한다

## 6. 백업 파이프라인 철거

- [ ] `remote_deploy.sh` — 백업 cron 설치 블록 제거
- [ ] 앱 VM에서 `sudo rm -f /etc/cron.d/judozoo-db-backup` — **배포는 이 파일을 지우지 않는다.** 남겨두면 매일 03:00에 실패 로그만 쌓인다
- [ ] `infra/deploy/backup_db.sh` 삭제, `deploy.yml`의 scp 대상에서 제외
- [ ] `docs/runbook-db-restore.md` 재작성 — 덤프 복원 절차가 **PITR 복원 절차**로 바뀐다
- [ ] GCS 백업 버킷은 **당분간 유지.** 이관 직전 덤프가 마지막 안전망이다

> **Cloud SQL 백업은 같은 프로젝트 안에 있다.** 프로젝트 단위 사고에는 현행과 마찬가지로 대비가 안 된다. 주 1회 `gcloud sql export sql`로 GCS에 논리 덤프를 남기는 것은 후속으로 둔다.

## 7. 사람이 붙는 경로

private IP라 로컬에서 직접 못 붙는다. **앱 VM을 경유**한다.

**일회성 쿼리** — SSH로 들어가서 클라이언트 컨테이너로:
```bash
gcloud compute ssh auto-trading-app-kiwoom-real --tunnel-through-iap --zone=asia-northeast3-a
sudo docker run --rm -it --network host mysql:8.4 mysql -h <private-ip> -u judozoo_app -p trading
```

**GUI(DBeaver 등)** — VM에서 Cloud SQL Auth Proxy를 띄우고 그 포트로 IAP 터널:
```bash
# VM에서
./cloud-sql-proxy --address 0.0.0.0 --port 3307 trading-496508:asia-northeast3:judozoo-db-prod
# 로컬에서
gcloud compute start-iap-tunnel auto-trading-app-kiwoom-real 3307 \
  --local-host-port=localhost:33061 --zone=asia-northeast3-a
```
로컬 포트를 33061로 잡는 것은 `settings.py` 기본값과 같아서다.

- [ ] 방화벽 `auto-trading-allow-ssh`에 3307 추가 (Auth Proxy를 쓸 경우)
- [ ] Auth Proxy 경로를 쓸 때만 VM SA에 `roles/cloudsql.client` 부여
      (private IP + 비밀번호 직결에는 **IAM 권한이 필요 없다**)

## 검증

- [ ] `gcloud sql instances describe judozoo-db-prod` — `ipAddresses`에 **PRIVATE만** 있고 PRIMARY(공인)가 없다
- [ ] 앱 VM에서 private IP:3306 연결 성공
- [ ] 로컬에서 **직접** private IP에 붙으면 실패한다 (경유 없이는 안 되는 게 정상)
- [ ] `https://judozoo.com/health/db` → `{"status":"UP","database":"UP"}`
- [ ] 앱이 **`root`가 아닌 `judozoo_app`으로** 붙는다
- [ ] Grafana 가입자 패널이 데이터를 그린다
- [ ] `grafana` 계정으로 `app_user` 외 테이블 SELECT가 **거부된다**
- [ ] PITR 동작 확인 — 테스트 테이블을 만들고 5분 뒤 삭제한 다음, 삭제 직전 시점으로 **복제본 복원**이 되는지 (운영 인스턴스를 되감지 말 것)
- [ ] 백업 설정 확인 — `backupConfiguration.binaryLogEnabled: true`
- [ ] 유지보수 창이 **일요일 04:00 KST**로 보이는지 콘솔에서 확인
- [ ] 앱 VM에 `/etc/cron.d/judozoo-db-backup`이 **없다**
- [ ] `terraform plan`이 앱 VM에 대해 **변경 0건**

## 알려진 함정

- **피어링 대역이 겹치면 연결 생성이 실패한다.** `default`는 auto-mode라 서브넷이 `10.128.0.0/9`에서 나온다. `10.100.0.0/24`는 그 바깥이다. 한 번 잡으면 넓히기 번거로우니 처음부터 `/24` 이상으로 잡는다.
- **`binary_log_enabled`가 없으면 PITR이 없다.** 자동 백업만으로는 초 단위 되감기가 안 된다 — 이 이관의 핵심 명분이 사라진다.
- **유지보수 창 `day`/`hour`는 UTC다.** 일요일 04:00 KST = `day=6, hour=19`. KST로 적으면 장중 재시작을 맞는다.
- **`default_time_zone`을 안 맞추면 DB 시각이 UTC가 된다.** 기존 컬럼이 시간대 없는 `DATETIME`에 KST 벽시계를 담고 있어 9시간이 어긋난다.
- **`--set-gtid-purged=OFF` 없이 덤프하면 복원이 막힌다.** 관리형 인스턴스는 GTID 상태를 직접 못 바꾼다.
- **삭제한 Cloud SQL 인스턴스 이름은 한동안 재사용할 수 없다.** 만들고 지우고를 반복하지 말 것. `deletion_protection`을 켜두는 이유이기도 하다.
- **`docker ps -qf name=mysql`은 실패하지 않고 조용히 건너뛴다.** 지우지 않으면 Grafana 계정이 갱신되지 않는데 배포는 성공으로 끝난다.
- **유지보수 재시작은 연결을 끊는다.** 폴러가 재연결을 견디는지 확인한다 — SQLAlchemy 엔진에 `pool_pre_ping`이 필요할 수 있다.
- **`db-g1-small`의 `max_connections`는 티어에 묶여 있다.** 백엔드 스레드풀 40 + Grafana 2를 감당하는지 실측한다.
- **스토리지 자동 증가는 늘기만 하고 줄지 않는다.** 한 번 커지면 되돌릴 수 없다.
- **기존 `mysql-data` 볼륨을 성급히 지우지 않는다.** 검증 후에도 최소 1주일은 롤백 경로로 남긴다.

## 후속

- 주 1회 `gcloud sql export sql` → GCS. Cloud SQL 백업은 같은 프로젝트라 프로젝트 단위 사고에 무방비다
- `docs/runbook-db-restore.md`를 PITR 기준으로 재작성 (§6)
- HA 전환 검토 — 존 장애까지 막으려면. 비용 2배
- `default-allow-internal` 축소 — 이번 안에서는 3306이 피어링으로 가서 급하지 않아졌다
- 앱 VM 이름을 `judozoo-server-prod`로 — DB가 빠져 나가면 재생성 비용이 크게 준다
