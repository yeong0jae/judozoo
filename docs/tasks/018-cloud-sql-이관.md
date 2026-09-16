# 018 — MySQL을 Cloud SQL(private IP)로 이관

> **2026-09-16 이관 완료.** 다운타임 63초(22:41:06 → 22:42:09 KST). 행 수 6개 테이블 일치,
> `broker_token` 보존(재기동이 브로커 발급을 소비하지 않음). 남은 일은 맨 아래 "후속".

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

> **이관 후 알게 된 것 — 야간 백업이 실제로는 돌지 않고 있었다.**
> 볼륨을 폐기하기 전 GCS를 확인하니 7일 보관에 객체가 **3개**뿐이었고, 그중 둘은 이름에 `predrop`이
> 붙은 수동 덤프였다. 자동 백업의 마지막 흔적은 2026-09-13이고 09-15·09-16 것은 없다.
> 즉 비교표의 "현행 RPO 24시간"은 **낙관적인 전제였고 실제로는 백업이 거의 없는 상태**로 돌고 있었다.
> 이관 판단의 근거가 약해진 게 아니라 더 강해진 셈이다. cron은 §6에서 걷어냈고 원인은 추적하지 않았다 —
> Cloud SQL 자동 백업이 대신하므로 실익이 없다.

## 진입 조건 (2026-09-15 실측)

| 항목 | 값 |
|---|---|
| DB 실 데이터 | **29.5MB** (`mysql-data` 볼륨은 650.6MB) |
| 최대 테이블 | `index_minute_candle` 7.5MB / 41,572행 |
| mysql 컨테이너 메모리 | 603.8MB |
| VM | `auto-trading-app-kiwoom-real` e2-medium, 컨테이너 7개, available 2,247MB |
| 부트디스크 | 19GB 중 9.4GB (52%) |
| 서브넷 `default` | `10.178.0.0/20` (asia-northeast3), auto-mode |
| 현재 백업 | `backup_db.sh` → GCS, 03:00 KST, 7일 보관, 설계상 **RPO 24시간** — **실제로는 더 나빴다**(아래) |
| 앱 DB 계정 | **`root`** (`docker-compose.yml:34`) |

## 설계 결정

| 항목 | 선택 | 이유 |
|---|---|---|
| 제품 | **Cloud SQL for MySQL** | PITR. 나머지 안은 논리적 손상을 못 되감는다 |
| 버전 | **`MYSQL_8_0`** | 8.4로 잡았다가 apply가 거부당했다 — 8.4는 기본 에디션이 `ENTERPRISE_PLUS`이고 그 에디션은 공유코어 티어를 안 받는다(`db-perf-optimized-N-*`만, 월 $200대). 8.0은 `ENTERPRISE`라 `db-g1-small`이 된다. 소스가 8.4지만 스키마가 평범한 DDL이라 복원에 문제없었다 |
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

- [x] `main.tf` — `google_project_service`에 **`servicenetworking.googleapis.com`**, **`sqladmin.googleapis.com`** 추가
- [x] `main.tf` — `google_compute_global_address` `judozoo-sql-peering`
      - `purpose = "VPC_PEERING"`, `address_type = "INTERNAL"`, `prefix_length = 24`, `address = "10.100.0.0"`
      - `network = "default"`
- [x] `main.tf` — `google_service_networking_connection`
      - `service = "servicenetworking.googleapis.com"`
      - `reserved_peering_ranges = [google_compute_global_address.sql_peering.name]`
- [x] `terraform apply` → 피어링 생성 확인
      ```
      gcloud compute networks peerings list --network=default
      ```

> **방화벽 규칙을 만들지 않는다.** 피어링 경로는 VPC 방화벽을 거치지 않는다. 전용 DB VM 안이었다면 여기서 `tcp:3306` 규칙이 필요했고, `default-allow-internal` 때문에 그 규칙이 실제 경계가 되지도 못했을 것이다.

## 2. Cloud SQL 인스턴스

- [x] `main.tf` — `google_sql_database_instance` `judozoo-db-prod`
      - `database_version = "MYSQL_8_0"`, `region = var.region`, `tier = "db-g1-small"`
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
- [x] `main.tf` — `google_sql_database` `trading` (charset `utf8mb4`)
- [x] `main.tf` — `google_sql_user` `judozoo_app` — 비밀번호는 `AT_DB_PASSWORD`
- [x] `main.tf` — `google_sql_user` `grafana` — 비밀번호는 `AT_GRAFANA_MYSQL_PASSWORD`
- [x] `outputs.tf` — `sql_private_ip` 출력
- [x] `terraform apply` → 인스턴스 생성 (5~10분 걸린다)

> **유지보수 창을 UTC로 지정한다.** `day`는 1(월)~7(일), `hour`는 0~23 **UTC**다. 일요일 04:00 KST를 원하면 `day = 6`(토), `hour = 19`다. KST 기준으로 적으면 장중에 재시작을 맞는다 — 폴러가 10초 주기로 도는 시스템이라 치명적이다.

## 3. 데이터 이관

> 실데이터 29.5MB라 덤프·복원은 수십 초다. 다운타임은 앱 재배포 시간이 지배한다.
> 장 마감 후, 20:00 애프터마켓 캡처가 끝나고 03:00 백업 전인 **21:00~23:00** 창에 한다.

- [x] (사전) 리허설 — 아래 전체를 한 번 돌려보고 행 수를 대조한다. **앱은 계속 기존 DB를 본다**
- [x] 앱 정지 — `docker compose stop backend` (폴러가 더 쓰지 않도록)
- [x] 덤프
      ```bash
      CID=$(sudo docker ps -qf name=mysql)
      sudo docker exec "$CID" sh -c \
        'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --events \
         --no-tablespaces --set-gtid-purged=OFF trading' > /tmp/final.sql
      ```
      `--set-gtid-purged=OFF`가 없으면 관리형 인스턴스에서 GTID 관련 구문으로 복원이 막힌다.
- [x] 복원 — 앱 VM에서 private IP로 직접 밀어넣는다
      ```bash
      SQL_IP=<terraform output sql_private_ip>
      sudo docker run --rm -i --network host mysql:8.4 \
        mysql -h "$SQL_IP" -u judozoo_app -p"$DB_PASSWORD" trading < /tmp/final.sql
      ```
- [x] **행 수 대조** — `index_minute_candle`, `market_investor_snapshot`, `futures_investor_snapshot`, `signal_event`, `stocks`, `app_user` 6개
- [x] 덤프 파일 삭제 (`/tmp/final.sql` — 평문 데이터다)

## 4. 앱 전환

- [x] `docker-compose.yml` — `mysql` 서비스 **제거**, `depends_on: mysql` 제거, `mysql-data` 볼륨 선언 제거
- [x] `docker-compose.yml` — `DB_HOST: ${DB_HOST}`, `DB_PORT: ${DB_PORT}`, **`DB_USERNAME: ${DB_USERNAME}`**
- [x] `remote_deploy.sh` — `secrets/.env`에 `DB_HOST`(private IP) · `DB_PORT=3306` · `DB_USERNAME=judozoo_app` 기록
- [x] `remote_deploy.sh` — `MYSQL_ROOT_PASSWORD` 줄 제거 (mysql 컨테이너가 없다)
- [x] 배포 → `/health/db` 200 확인
- [x] 화면 육안 확인 — 주도주 후보 / 시황 / 실시간 로그

> **`settings.py`의 `DB_PORT` 기본값이 33061이다.** compose에서 3306을 명시 주입해야 한다.
> **private IP를 시크릿에 넣지 않는다.** 비밀이 아니고, `terraform output`이 정답을 갖고 있다.

## 5. Grafana 계정 권한

기존 `remote_deploy.sh`는 `docker exec`로 mysql 컨테이너에 들어가 계정을 만들고 GRANT했다. **Cloud SQL에는 `docker exec`할 컨테이너가 없다.**

> **Cloud SQL이 만드는 사용자는 기본이 `cloudsqlsuperuser`다.** 계정 이름만 바꾸면 권한은 전권 그대로다.
> 그래서 이 단계는 "GRANT 추가"가 아니라 **"역할 회수 후 재부여"** 다. 빠뜨리면 Grafana가
> 지금(`SELECT` on 1개 테이블)보다 **넓은** 권한을 갖는다 — 유일하게 후퇴하는 칸이다.

- [x] 계정 생성은 Terraform(`google_sql_user.grafana`)이 맡는다
- [x] 역할 회수 + 재부여 (앱 VM에서 mysql 클라이언트 컨테이너로 1회 실행)
      ```sql
      REVOKE 'cloudsqlsuperuser'@'%' FROM 'grafana'@'%';
      GRANT SELECT ON trading.app_user TO 'grafana'@'%';
      ```
      **`REVOKE ALL PRIVILEGES`는 쓰지 말 것.** `judozoo_app`에 `sys`·`mysql` 부분 revoke가 걸려
      있어 남의 전권을 회수하지 못하고 `ERROR 3879`로 끊긴다. 그 줄에서 멈추면 뒤의 GRANT가
      실행되지 않아 **Grafana가 아무것도 못 읽는 상태로 남는다.**
      역할만 회수해도 `USAGE`만 남으므로 `REVOKE ALL`은 애초에 필요 없다.
- [x] 결과 확인 — `app_user`는 읽히고 `signal_event`는 `ERROR 1142`로 막힌다
- [x] `remote_deploy.sh`의 기존 `docker ps -qf name=mysql` 블록 삭제
      (컨테이너가 없으면 조용히 건너뛴다 — **실패하지 않고 잘못 동작한다**)
- [x] `observability/grafana/provisioning/datasources/mysql.yaml` — `url: mysql:3306` → `url: $DB_HOST:3306`

> **이 GRANT는 배포로 재적용되지 않는다.** `google_sql_user`를 파괴·재생성하면 권한이 초기값
> (`cloudsqlsuperuser`)으로 돌아가므로 위 SQL을 다시 실행해야 한다.

> **앱 계정(`judozoo_app`)은 좁히지 않았다.** 지금도 전권이지만 옮기기 전 `root`와 같은 수준이라
> 후퇴는 아니다. 좁히려면 마이그레이션 적용용 관리자 계정이 따로 필요해진다 — 후속으로 둔다.

## 6. 백업 파이프라인 철거

- [x] `remote_deploy.sh` — 백업 cron 설치 블록 제거
- [x] 앱 VM에서 `sudo rm -f /etc/cron.d/judozoo-db-backup` — **배포는 이 파일을 지우지 않는다.** 남겨두면 매일 03:00에 실패 로그만 쌓인다
- [x] `infra/deploy/backup_db.sh` 삭제, `deploy.yml`의 scp 대상에서 제외
- [x] `docs/runbook-db-restore.md` **삭제** — 내용 전체가 VM 안 mysql 컨테이너를 전제로 한 절차였다. PITR 복원은 콘솔이나 `gcloud sql instances clone --point-in-time`으로 하며, 문서로 붙들 만큼 손이 많이 가지 않는다
- [x] GCS 백업 버킷은 **당분간 유지.** 직전 야간 덤프가 들어 있고, 그날치는 앱 VM의 `mysql-data` 볼륨이 아직 들고 있다

> **Cloud SQL 백업은 같은 프로젝트 안에 있다.** 프로젝트 단위 사고에는 현행과 마찬가지로 대비가 안 된다. 주 1회 `gcloud sql export sql`로 GCS에 논리 덤프를 남기는 것은 후속으로 둔다.

## 7. 사람이 붙는 경로

private IP라 로컬에서 직접 못 붙는다. **앱 VM을 경유**한다.

**일회성 쿼리** — SSH로 들어가서 클라이언트 컨테이너로:
```bash
gcloud compute ssh auto-trading-app-kiwoom-real --tunnel-through-iap --zone=asia-northeast3-a
sudo docker run --rm -it --network host mysql:8.4 mysql -h <private-ip> -u judozoo_app -p trading
```

**GUI(DataGrip·DBeaver 등)** — **SSH 포트 포워딩 한 줄.** Auth Proxy는 필요 없다.
```bash
gcloud compute ssh auto-trading-app-kiwoom-real --tunnel-through-iap \
  --zone=asia-northeast3-a -- -N -L 33061:10.100.0.3:3306
```
이 창을 띄워둔 채 GUI에서 `localhost:33061` / `judozoo_app` / DB `trading`으로 붙는다.
비밀번호는 `gcloud secrets versions access latest --secret=AT_CLOUDSQL_APP_PASSWORD`.

`start-iap-tunnel`은 **VM만** 대상으로 삼아 Cloud SQL 주소에 직접 못 뚫는다. VM 위의 무언가가
중계해야 하는데, 그 중계자가 Auth Proxy일 필요는 없다 — `ssh -L`이 이미 그 일을 한다.
로컬 포트를 33061로 잡는 것은 `settings.py` 기본값과 같아서다(터널만 열면 로컬 백엔드가
설정 변경 없이 운영 DB를 본다 — 편리한 만큼 위험하므로 상시로 쓰지 않는다).

> Auth Proxy가 값을 하는 경우는 IAM 데이터베이스 인증(비밀번호 없는 접속)이나 인스턴스까지의
> 자동 TLS가 필요할 때다. 여기서는 SSH·IAP가 이미 구간을 암호화한다.

- [x] ~~방화벽에 3307 추가~~ / ~~VM SA에 `roles/cloudsql.client`~~ — **둘 다 필요 없었다.**
      Auth Proxy를 세울 것 없이 **SSH 포트 포워딩**이 같은 일을 한다. 기존 IAP SSH(22)만 쓰므로
      새 방화벽 규칙도, 추가 IAM도 없다. 2026-09-16 노트북에서 실측 확인.

## 검증

- [x] `gcloud sql instances describe judozoo-db-prod` — `ipAddresses`에 **PRIVATE만** 있고 PRIMARY(공인)가 없다
- [x] 앱 VM에서 private IP:3306 연결 성공
- [x] 로컬에서 **직접** private IP에 붙으면 실패한다 (경유 없이는 안 되는 게 정상)
- [x] `https://judozoo.com/health/db` → `{"status":"UP","database":"UP"}`
- [x] 앱이 **`root`가 아닌 `judozoo_app`으로** 붙는다
- [x] Grafana 가입자 패널이 데이터를 그린다
- [x] `grafana` 계정으로 `app_user` 외 테이블 SELECT가 **거부된다**
- [x] PITR 동작 확인 — 2026-09-16 검증. `pitr_test` 테이블을 만들고(14:08:32Z) 3분 뒤 DROP(14:11:33Z),
      `gcloud sql instances clone judozoo-db-prod <임시> --point-in-time=2026-09-16T14:10:00Z`로 복제본을 세우니
      지운 테이블이 그대로 살아 있었고 운영 데이터(`index_minute_candle` 42,847행)도 온전했다. 복제본은 삭제.
      **운영을 되감지 않는다** — 되감으면 그 이후의 정상 데이터까지 날아간다. 복제본에서 필요한 것만 퍼온다.
      복제본은 `deletion_protection`을 물려받으므로 지우기 전에 `gcloud sql instances patch <이름> --no-deletion-protection`이 필요하다.
- [x] 백업 설정 확인 — `backupConfiguration.binaryLogEnabled: true`
- [x] 유지보수 창이 **일요일 04:00 KST**로 보이는지 콘솔에서 확인
- [x] 앱 VM에 `/etc/cron.d/judozoo-db-backup`이 **없다**
- [x] `terraform plan`이 앱 VM에 대해 **변경 0건**

## 알려진 함정

> 아래 ★ 표시는 **이번 이관에서 실제로 밟은 것**이다.

- **★ MySQL 8.4는 공유코어 티어를 못 쓴다.** 기본 에디션이 `ENTERPRISE_PLUS`로 잡히고 `db-g1-small`을 거부한다(`Invalid Tier ... for (ENTERPRISE_PLUS) Edition`). 8.0으로 내리면 `ENTERPRISE`가 되어 통과한다. 실패해도 인스턴스는 생성되지 않으므로 되돌릴 것은 없다.
- **★ `secrets/.env`는 배포 SA의 홈에 있다.** `/home/sa_<숫자>/`이고, IAP SSH로 들어간 내 홈이 아니다. compose 프로젝트 이름도 그 디렉터리 이름에서 나오므로, 손으로 조작할 때는 `docker compose -p sa_<숫자> --project-directory /home/sa_<숫자>`를 써야 기존 컨테이너를 잇는다. 모르고 내 홈에서 돌리면 **같은 이름의 컨테이너가 따로 뜬다.**
- **★ compose의 `${VAR}` 보간은 `env_file`을 읽지 못한다.** `DB_HOST: ${DB_HOST}`라고 쓰면 빈 값이 된다. `environment:`에서 빼고 `env_file`이 컨테이너에 직접 넣게 해야 한다(`environment:`에 적으면 `env_file`을 덮어쓰기까지 한다). grafana 쪽 주석에 이미 같은 교훈이 적혀 있었다.
- **★ 손으로 재기동할 때 AR 로그인이 필요하다.** prod override가 `pull_policy: always`라 인증 없이는 `authentication failed`로 멈춘다. `gcloud auth print-access-token | sudo docker login -u oauth2accesstoken --password-stdin https://<region>-docker.pkg.dev`.
- **★ 덤프 파일을 지워야 한다.** `/tmp/final.sql`은 25MB 평문 DB다.

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

- ~~옛 `mysql-data` 볼륨·`AT_DB_PASSWORD` 폐기~~ — 2026-09-16 완료. 볼륨 삭제로 부트디스크 52% → 48%,
  시크릿은 terraform에서 제거해 destroy

- `google_artifact_registry_repository.docker`가 **매 plan마다 in-place 변경으로 뜬다.** `cleanup_policies`를 같은 내용으로 다시 쓰는 수렴하지 않는 drift다 — 이미지가 지워지지는 않지만 plan이 늘 깨끗하지 않아 진짜 변경을 가린다

- 주 1회 `gcloud sql export sql` → GCS. Cloud SQL 백업은 같은 프로젝트라 프로젝트 단위 사고에 무방비다
- HA 전환 검토 — 존 장애까지 막으려면. 비용 2배
- `default-allow-internal` 축소 — 이번 안에서는 3306이 피어링으로 가서 급하지 않아졌다
- 앱 VM 이름을 `judozoo-server-prod`로 — DB가 빠져 나가면 재생성 비용이 크게 준다
