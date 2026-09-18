# 021 — Tempo 트레이스 도입

Goal: [020](020-prometheus-메트릭.md)에서 메트릭까지 온 관측 스택에 **트레이스** 축을 더한다.
백엔드를 OpenTelemetry로 계측하고, 앱 VM의 alloy가 OTLP로 받아 ops VM의 Tempo로 밀어 넣는다.
로그 한 줄에서 그 요청의 트레이스로 **점프**하는 것까지가 이 작업의 끝이다.

> **왜 트레이스인가.** 020이 "무엇이 잘못됐나"에 답한다면 트레이스는 "**어디서** 느렸나"에 답한다.
> 지금 답할 수 없는 질문 —
> ① **"주도주 스캔 한 번이 왜 40초 걸렸나."** 잡 하나가 브로커 API 여러 개로 팬아웃하는데,
> 메트릭은 vendor별 p95만 줄 뿐 **이 잡 실행이 어느 호출에서 멈췄는지**는 모른다
> ② **"이 5xx 응답이 무엇 때문이었나."** 로그에 에러 줄은 있지만 그 요청이 거쳐간 DB·브로커
> 호출을 이어 볼 방법이 없다. 시각으로 눈대중할 뿐이다
> ③ 020 §6 패널이 "브로커 p95 20초"를 보여줘도 **리미터 대기인지 브로커가 느린 건지** 못 가른다.
> 스팬이 나뉘면 그 자리에서 갈린다

## 진입 조건 (2026-09-18 11:1x 실측, 장중)

### 트레이스 발생량 — 020 메트릭으로 직접 쟀다

| 항목 | 초당 | 근거 |
|---|---|---|
| API 요청 | **0.336** | `sum(rate(http_requests_total[30m]))` |
| 스케줄러 잡 | **0.167** | `sum(rate(scheduler_job_runs_total{result="success"}[30m]))` |
| 브로커 호출 | **1.129** | `sum(rate(http_client_requests_total[30m]))` |

- **루트 스팬(=트레이스) 0.50/s → 하루 약 4.3만 개**
- 전체 스팬은 여기에 브로커·DB 자식이 붙어 **약 3/s → 하루 26만 개, 100MB 안팎**
- 이 규모면 **샘플링이 필요 없다.** 100% 수집한다 — 샘플링은 "느렸던 그 한 건"을 버릴 위험을
  지는 대신 용량을 아끼는 거래인데, 아낄 용량이 없다

### ops VM 여유 — **이번 작업의 제약**

```
Mem:  total 1960MB   available 1290MB
  grafana     254MB   (019 측정 81MB → 어제 125MB → 오늘 254MB)
  prometheus  128MB   (어제 41MB)
  loki        171MB   (019 측정 71MB → 어제 95MB)
  합계        553MB
```

- 디스크는 넉넉하다 — 19GB 중 **15GB 여유**, `loki-data` 5.7MB, `prometheus-data` 14MB
- **문제는 메모리다.** 세 컨테이너가 019 측정치(합계 247MB)의 2배를 넘었고 아직 안정되지 않았다
- Tempo 단일 바이너리는 이 규모에서 300~400MB를 본다. 지금 붙이면 1.9GB 중 여유가 800MB 밑으로
  떨어지고, **OOM이 나면 관측 스택 전체가 같이 죽는다** — 019가 막으려던 바로 그 상황이다

### 백엔드 구조 (계측 대상)

- **uvicorn 단일 프로세스.** `--workers` 없음 — 020과 같은 전제
- `BackgroundScheduler`가 같은 프로세스의 **스레드풀**에서 잡 5개를 돌린다
- 브로커 클라이언트 4곳(`kis`·`kiwoom`·`toss`·`yahoo`) 모두 **동기** `httpx.Client`,
  커스텀 transport 체인 `MeteredTransport(RateLimitedTransport(HTTPTransport))`
- DB는 `library/db.py`의 `get_engine()` 하나 (SQLAlchemy + PyMySQL)
- 로그는 `library/logging_config.py`의 `JsonFormatter` — 한 줄 JSON, `extra=` 필드를 그대로 싣는다

## 설계 결정

| 항목 | 선택 | 이유 |
|---|---|---|
| **ops VM 사양** | **e2-small → e2-medium (4GB)** | 위 실측 근거. 월 +$13. 1.9GB에 Tempo를 욱여넣고 OOM을 걱정하느니 올린다 — 019가 세운 "앱이 죽어도 조사 도구는 산다"가 메모리 압박으로 무너지면 의미가 없다 |
| 수집 경로 | **backend → alloy(OTLP) → Tempo** | 로그·메트릭과 같은 방향. alloy가 배치·재시도를 맡고 VM 경계를 넘는 경로가 하나로 유지된다 |
| 저장소 | **로컬 파일시스템 + 명명 볼륨** | 하루 100MB × 7일 = 700MB, 디스크 15GB 여유. GCS는 이 규모에서 얻는 게 없다 (019·020과 같은 판단) |
| 보존 | **7일** | Loki와 맞춘다. 로그에서 트레이스로 점프하는 게 목적인데 한쪽만 남아 있으면 링크가 깨진다 |
| 샘플링 | **없음 (100%)** | 위 실측 근거 |
| 자동 계측 | **FastAPI · httpx · SQLAlchemy** | 요청 경로는 이 셋이 덮는다 |
| 잡 경계 | **수동 스팬** | 자동 계측이 못 덮는다. **정작 제일 중요한 게 이 경로다**(위 질문 ①) |
| 로그 연결 | `JsonFormatter`에 `trace_id` 필드 추가 | OTel의 logging 계측 대신 직접 넣는다 — 필드 이름을 우리가 정해야 Grafana `derivedFields`가 건다 |
| `/health` | **제외** | 10초마다 헬스체크, 5초마다 화면 폴링. 020에서 메트릭에 한 것과 같은 이유 |

### 목표 구성

```
        ┌──────────────────────────────────────────────┐
        │ 앱 VM  judozoo-server-prod                   │
        │                                              │
        │  backend ── OTLP ──▶ alloy                   │
        │     │                  │                     │
        │     └── /metrics ──────┤                     │
        │                        ├── logs ────▶ 3100   │
        │                        ├── metrics ─▶ 9090   │
        │                        └── traces ──▶ 4317   │
        └────────────────────────┬─────────────────────┘
        ┌────────────────────────▼─────────────────────┐
        │ ops VM  judozoo-ops-prod  (e2-medium 승급)    │
        │   loki:3100    prometheus:9090   tempo:4317  │
        │              grafana:3000                    │
        │                   └─ Loki 로그 ─┬─▶ Tempo 트레이스
        └──────────────────────────────────┴───────────┘
                         derivedFields (trace_id)
```

### 비용

| 항목 | 월 |
|---|---|
| ops VM `e2-small` → `e2-medium` | **+$13** |
| Tempo 저장소 | $0 (부트디스크) |
| **021 추가분** | **+$13** |

[020](020-prometheus-메트릭.md) 시점 ~$73 → **~$86**. 상한 $120에 여유가 남는다.

---

## 1. ops VM 승급

- [x] `main.tf` — ops 인스턴스 `machine_type = "e2-medium"`
- [x] **`allow_stopping_for_update = true` 추가.** 없으면 terraform이 머신 타입 변경을 거부한다
- [x] `terraform plan`이 **update in-place**인지 확인 — `0 to add, 2 to change, 0 to destroy`. — `destroy`가 뜨면 멈춘다.
      명명 볼륨이 부트디스크에 있어 재생성되면 **로그·메트릭이 전부 날아간다**
- [x] `terraform apply` (2026-09-18). ops VM이 잠깐 내려간다. 앱 VM은 영향 없고, alloy WAL이 그 사이를 버틴다
      (019에서 재부팅으로, 020에서 out-of-order 창으로 각각 확인한 경로다)
- [x] 승급 후 실측 — **available 1290MB → 3346MB**(total 3908). 컨테이너 셋 자동 복귀,
      **승급 전 데이터 보존 확인**(6시간 전 샘플 조회됨, TSDB 14MB 유지, `up` 3개)

## 2. 백엔드 계측

- [x] `pyproject.toml` — OTel 의존성 (sdk 1.44.0, instrumentation 0.65b0)
      ```
      opentelemetry-sdk
      opentelemetry-exporter-otlp-proto-grpc
      opentelemetry-instrumentation-fastapi
      opentelemetry-instrumentation-httpx
      opentelemetry-instrumentation-sqlalchemy
      ```
- [x] `library/tracing.py` 신설 — TracerProvider 설정, OTLP exporter, 자동 계측 등록.
      `settings.py`에 엔드포인트와 on/off 스위치를 둔다 (`SCHEDULERS_ENABLED`와 같은 결)
- [x] `main.py` lifespan에서 초기화. **테스트에서는 켜지 않는다**
- [x] `/health`·`/metrics` 제외 — `OTEL_PYTHON_FASTAPI_EXCLUDED_URLS`
- [x] httpx 계측 확인 — 커스텀 transport 체인 **위**를 감싸므로 스팬에 리미터 대기가 포함된다.
      020의 `http_client_request_duration_seconds`와 같은 의미가 되어 서로 대조가 된다.
      대기와 왕복을 가르려면 별도 스팬이 필요하다 — 후속
- [x] SQLAlchemy 계측은 `get_engine()`이 만든 엔진에 건다

## 3. 스케줄러 잡 스팬 (수동)

**자동 계측이 못 덮는 경로이고, 위 질문 ①이 정확히 여기다.**

- [x] `library/tracing.py`에 `traced_job(job_id)` 데코레이터
- [x] 잡 함수 5개에 적용 — `leadingstock` 3개, `market` 1개, `stock` 1개.
      잡 id는 `leadingstock/scheduler.py`에 이미 상수로 있다(`_SIGNAL_JOB` 등, 020에서 뽑았다)
- [x] 리스너(`metrics.on_job_event`)와 **역할이 다르다** — 리스너는 잡이 끝난 뒤 집계하고,
      데코레이터는 잡이 도는 **동안** 스팬을 열어 자식(브로커·DB)이 붙게 한다. 둘 다 필요하다
- [x] 삼킨 예외를 스팬에도 기록한다 — `span.record_exception()`.
      020에서 배운 것: 잡이 `log.warning`으로 흘리면 밖에서는 성공으로 보인다

## 4. 로그 ↔ 트레이스 연결

- [x] `logging_config.py`의 `JsonFormatter.format()`에 현재 스팬의 `trace_id` 추가.
      유효한 스팬이 없으면 필드를 넣지 않는다(폴러 밖 로그까지 지저분해지지 않게)
- [x] Grafana Loki 데이터소스에 `derivedFields` — `trace_id` → Tempo로 점프
- [x] **이 단계가 이 작업의 실제 상금이다.** 없으면 Tempo는 따로 열어보는 또 하나의 화면일 뿐이다

## 5. alloy — OTLP 수신·전달

- [x] `config.alloy`에 `otelcol.receiver.otlp` (grpc 4317) + `otelcol.processor.batch`
      + `otelcol.exporter.otlp` → ops VM
- [x] `docker-compose.yml` — backend가 `alloy:4317`로 보내도록 환경변수. 같은 compose 네트워크라
      호스트 포트는 **필요 없다**
- [x] **설정 오류는 alloy를 통째로 죽인다**(020 함정). `remote_deploy.sh`의 alloy 생존 검사가
      이미 이걸 잡는다 — 검사가 그대로 유효한지 확인만 한다

## 6. ops 스택 — Tempo

- [x] `observability/tempo/tempo.yaml` — 단일 바이너리 모드, 로컬 스토리지, 보존 7일
- [x] `docker-compose.ops.yml` — `tempo` 서비스. 이미지 태그는 **정확한 패치 버전 고정**
      (loki `3.4.2`·grafana `11.5.2`·prometheus `v3.14.0`과 같은 원칙)
- [x] `ports` — `4317:4317`(OTLP 수집) + `127.0.0.1:3200:3200`(헬스체크용). 아래 함정 참고 — alloy가 다른 머신에서 붙는다 (Loki 3100·Prometheus 9090과 같은 이유)
- [x] 명명 볼륨 `tempo-data`
- [x] `docker-compose.ops.prod.yml` — `restart: unless-stopped`
- [x] Grafana 데이터소스 `tempo.yaml` — `isDefault`는 Loki에 그대로 둔다

## 7. 방화벽

- [x] `main.tf` — `judozoo-ops-allow-otlp`, `tcp:4317`, `source_tags = ["auto-trading"]`
- [x] 기존 규칙에 포트를 끼워 넣지 않는다 — 이름이 거짓말이 된다 (020 §5와 같은 판단)
- [ ] `terraform apply`는 배포 파이프라인에 없다. 직접 돌린다

## 8. 배포

- [x] `remote_deploy_ops.sh` 헬스체크에 Tempo 추가 (`/ready`)
- [x] 마운트 검사에 Tempo 데이터소스 추가 — 020 §7의 stale bind-mount 방어
- [x] 배포 순서는 그대로 `deploy-ops` → `deploy`

## 검증

### 로컬 end-to-end (2026-09-18, 배포 전)

Tempo + alloy를 실제로 띄우고 **진짜 스팬을 흘렸다.** 조회된 트레이스 —

```
서비스: backend
  루트  job signal-event-poller   {scheduler.job_id: signal-event-poller}
    └─ GET /api/dostk/sect
    └─ SELECT signal_event
```

| 항목 | 결과 |
|---|---|
| backend → alloy → Tempo | 흘렀다. Tempo `/api/traces/<id>`로 조회 확인 |
| 잡 스팬 부모-자식 | **자식이 루트에 붙는다** — §3의 목적 그대로 |
| 로그 `trace_id` | JSON 로그에 실렸고, **그 id로 트레이스를 꺼냈다**(§4 연결 증명) |
| alloy 컴포넌트 | receiver/processor/exporter 셋 다 평가 통과, 에러 0건 |
| 백엔드 테스트 | `548 passed` (기존 543 + 트레이스 5) |
| Tempo 기동 | 2.10.8이 이 설정 그대로 `/ready` 200 |


- [ ] backend 컨테이너 안에서 트레이스가 생성된다 (로그에 `trace_id` 필드가 보인다)
- [ ] Tempo에 트레이스가 도착한다 — Grafana Explore에서 조회
- [ ] **API 요청 트레이스에 DB 스팬이 자식으로 붙는다**
- [ ] **스케줄러 잡 트레이스에 브로커 호출이 자식으로 붙는다** ← 이 작업의 핵심
- [ ] Loki 로그 줄에서 `trace_id`를 눌러 Tempo로 점프된다
- [ ] `/health` 트레이스가 **없다**
- [ ] 승급 후 ops VM 메모리 여유 — Tempo 포함 실측치를 기록한다
- [ ] 24시간 뒤 `tempo-data` 실크기. 추정 100MB/일이 맞는지 본다
- [ ] 배포 알림(020 §9)이 정상 동작 — 이번 배포에도 Slack 메시지가 온다

## 알려진 함정 (착수 전에 아는 것)

- **compose 네트워크 안에서 되는 것과 호스트에서 되는 것은 다르다.** Tempo는 조회 포트 3200을
  Grafana만 쓰므로 호스트에 낼 필요가 없다고 보고 `4317`만 퍼블리시했다. 그런데
  `remote_deploy_ops.sh`의 헬스체크는 **호스트에서** `localhost:3200`을 친다 — 첫 배포가
  `tempo=0`으로 24번 재시도 후 실패했다. 호스트에서 본 응답은 503이 아니라 **`000`(연결 거부)**였다.
  로컬 검증을 `docker run --network t-net`으로 **네트워크 안에서만** 해서 이 간극을 못 봤다.
  `127.0.0.1:3200:3200`으로 고쳤다 — 호스트에서만 닿고 VPC에는 안 열린다(grafana 3000이
  0.0.0.0이어야 하는 것은 IAP가 네트워크 인터페이스로 붙기 때문이라 사정이 다르다).
  **503과 000을 구분해야 한다** — 503은 "떴는데 아직 준비 안 됨", 000은 "닿지도 못함"이다.
- **Tempo 준비에 15~18초 걸린다.** 기동 직후 `/ready`는 503이다. 실측(ops VM 15초, 로컬 18초)이라
  헬스체크 예산 120초에는 여유가 있지만, 한 번 보고 판단하면 안 되는 값이다.

- **Tempo 3.x는 설정 스키마가 다르다.** 최신 안정판이라고 `3.0.3`을 잡았다가 기동에 실패했다 —
  ```
  failed parsing config: field compactor not found in type app.Config
                         field ingester not found in type app.Config
  ```
  3.0은 아키텍처를 바꿔 `compactor`·`ingester`가 `backend-scheduler.provider.work.*` 계열로
  옮겨갔다. 단일 인스턴스 하나에 새 구조를 들일 이유가 없어 **2.10.8**로 갔다(3.0.3과 같은 날
  릴리스된 유지보수 버전). 3.x로 옮기는 건 설정을 새로 쓰는 별도 작업이다.
- **OTel provider는 프로세스당 한 번만 세워진다.** 두 번째 `set_tracer_provider`는 조용히
  무시된다. 테스트에서 케이스마다 새로 만들었더니 두 번째부터 엉뚱한 exporter를 보며 실패했다 —
  provider는 한 번 세우고 exporter를 비우는 구조여야 한다.

- **잡 스팬은 자동으로 안 생긴다.** OTel에 APScheduler 계측이 없다. 수동으로 열지 않으면
  브로커 호출 스팬들이 부모 없이 흩어져, 정작 보려던 "이 잡 실행이 어디서 멈췄나"를 못 본다.
- **스레드 경계를 넘으면 컨텍스트가 안 따라간다.** OTel 컨텍스트는 스레드 로컬이다.
  잡은 스레드풀에서 돌지만 **스팬을 잡 함수 안에서 열므로** 문제가 되지 않는다 — 다만
  나중에 잡 안에서 스레드를 더 띄우면 그때는 명시적으로 넘겨야 한다.
- **`trace_id` 없는 로그가 정상이다.** 기동·스케줄러 밖 로그에는 스팬이 없다. 필드가 항상
  있을 거라 가정하고 Grafana 쿼리를 짜면 그 줄들이 사라진다.
- **ops VM 승급이 `destroy`로 계획되면 멈춘다.** 명명 볼륨이 부트디스크에 있어 재생성되면
  로그·메트릭이 전부 날아간다. `allow_stopping_for_update`로 in-place가 되는지 반드시 본다.
- **alloy 설정 오류는 로그 수집까지 멈춘다**(020에서 실측). OTLP 블록을 잘못 넣으면 메트릭·로그가
  같이 죽는다. `remote_deploy.sh`의 alloy 생존 검사가 유일한 방어선이다.
- **Tempo를 붙이는 순간 ops VM 메모리가 이 스택의 병목이 된다.** 승급 후에도 세 컨테이너가
  계속 자라는 중이라(019 대비 2배) 한 번 재고 끝낼 값이 아니다.

## 후속

- 리미터 대기와 브로커 왕복을 **별도 스팬으로 분리** — 020 §후속과 같은 항목이다.
  트레이스가 있으면 메트릭보다 여기서 가르는 게 자연스럽다
- 프론트엔드 트레이스 — 사용자가 겪은 시간부터 이어 보려면 브라우저까지 계측해야 한다.
  이번 범위 밖
- exemplar — Prometheus 히스토그램에서 해당 트레이스로 바로 점프. 메트릭↔트레이스 연결까지
  닫으려면 필요하다
