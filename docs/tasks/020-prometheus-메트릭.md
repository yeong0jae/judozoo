# 020 — Prometheus 메트릭 도입

> **2026-09-17 적용 완료.** ops VM에 `prometheus`(41MB)가 떴고, 앱 VM의 alloy가 백엔드·호스트
> 메트릭을 remote_write로 밀어 넣는다. `up` 타깃 3개가 모두 1이다.
>
> **cadvisor는 배포 후에 뺐다.** 이 호스트의 Docker가 containerd 이미지 스토어를 써서 도커
> 컨테이너를 하나도 인식하지 못했다(아래 함정). 컨테이너 메모리는 backend가 스스로 내는
> `process_resident_memory_bytes`로 본다.
>
> **Grafana 프로비저닝은 두 번째 배포까지 깨져 있었다.** `remote_deploy_ops.sh`에
> `--force-recreate`가 없어 grafana 컨테이너가 8시간 전 것 그대로였고, `rm -rf` + 재-scp로
> 교체된 inode를 계속 가리켰다 — Prometheus 데이터소스와 메트릭 대시보드가 한 번도 올라간
> 적이 없었다(아래 함정). 세 번째 배포에서 해결 —
> `inserting datasource from configuration name=Prometheus uid=prometheus`.

Goal: 지금 **로그밖에 없는** 관측 스택에 메트릭 축을 더한다. 앱 VM의 `alloy`가 백엔드·호스트·컨테이너 메트릭을 걷어 ops VM의 Prometheus로 **remote_write**하고, Grafana가 Loki 옆에 그것을 붙인다.

> **트레이스(Tempo)는 이번 범위가 아니다.** 메트릭이 먼저인 이유는 지금 답할 수 없는 질문이 대부분 메트릭 질문이기 때문이다 —
> ① **"08:00 폴러가 돌긴 했나."** `leadingstock.scheduler.poll_signal_events`는 10초마다 돌면서 예외를 `log.warning`으로 삼킨다. 계속 실패해도 화면은 멀쩡하고 로그 한 줄만 조용히 쌓인다
> ② **"KIS 에러율이 언제부터 올랐나."** 브로커 호출은 vendor 4곳으로 흩어져 있고, 실패는 개별 로그 라인일 뿐 추세가 아니다
> ③ **"backend 메모리가 새고 있나."** 지금은 SSH로 `docker stats`를 치는 것 외에 방법이 없다

## 진입 조건 ([019](019-관측-vm-분리.md) 완료 상태)

| 위치 | 현재 |
|---|---|
| 앱 VM `judozoo-server-prod` (e2-medium, 4GB) | `caddy · frontend · backend · alloy` |
| ops VM `judozoo-ops-prod` (e2-small, 2GB) | `loki · grafana` — 실사용 합계 **152MB** |
| alloy → Loki | ops VM 내부 DNS로 push (VM 경계를 이미 넘고 있다) |
| 방화벽 | `judozoo-ops-allow-loki` = `tcp:3100` ← tag `auto-trading` |
| 부트디스크 | 양쪽 20GB. ops의 `loki-data`는 7일치 **16.8MB** |

- 백엔드는 **uvicorn 단일 프로세스**다(`--workers` 없음). 멀티프로세스 레지스트리가 필요 없다 — 이번 작업이 가장 단순해지는 지점이다
- APScheduler `BackgroundScheduler`가 **같은 프로세스 안**에서 돈다. 잡 메트릭이 웹 메트릭과 같은 레지스트리에 담긴다
- 백엔드 컨테이너는 `expose: 8000`뿐 — 호스트 포트가 없다. nginx는 `/api/`만 프록시한다
- 브로커 클라이언트는 vendor 4곳(`kis · kiwoom · toss · yahoo`)에 각각 `get_client()`로 `httpx.Client`를 만든다. KIS에는 이미 `RateLimitedTransport`라는 커스텀 transport 선례가 있다

## 설계 결정

| 항목 | 선택 | 이유 |
|---|---|---|
| 수집 방향 | **push (alloy remote_write)** | Prometheus가 ops에서 앱 VM을 pull하려면 backend에 **호스트 포트를 내야** 하고, `default-allow-internal` 아래에서는 그게 곧 VPC 전체 노출이다. alloy는 이미 앱 VM에 있고 이미 ops로 push한다 — 방향을 하나로 유지한다 |
| 노드·컨테이너 메트릭 | **alloy 내장 exporter** | `prometheus.exporter.unix` · `prometheus.exporter.cadvisor`가 alloy 안에 있다. node-exporter·cAdvisor 컨테이너를 따로 띄우지 않는다 |
| Prometheus 위치 | **ops VM** | 019의 목적(앱 VM이 죽어도 조사 도구가 산다)이 메트릭에도 그대로 적용된다 |
| ops VM 사양 | **e2-small 유지** | 현재 152MB / 2GB. 이 규모의 Prometheus는 200~300MB 선이라 승급이 필요 없다. **Tempo까지 얹을 때 다시 잰다** |
| 데이터 디스크 | **별도 PD 없음** | 019와 같은 판단. 대신 `--storage.tsdb.retention.size`로 부트디스크를 지킨다 |
| 보존 | **30일 + 4GB 상한** | 둘 중 먼저 닿는 쪽이 이긴다. 로그(7일)보다 긴 이유는 메트릭이 "지난달 대비"를 보는 물건이기 때문이다. 실제 크기는 한 달 뒤 재본다 |
| 스크레이프 주기 | **30s** | 폴러 최단 주기가 10s지만, 카운터는 30s로도 추세가 보인다. 15s로 낮추면 시계열 저장량이 2배다 |
| 웹 계측 | **prometheus-fastapi-instrumentator** | 라우트 템플릿 단위 히스토그램 + `/metrics` 노출을 두 줄로 끝낸다. 커스텀 메트릭은 그 아래 `prometheus_client`를 직접 쓴다 |
| 알림 | **이번 범위 아님** | Alertmanager를 세우지 않는다. 필요해지면 Grafana 알림이 같은 데이터소스를 그대로 쓴다 |

### 목표 구성

```
        ┌────────────────────────────────────────┐
        │ 앱 VM  judozoo-server-prod             │
        │                                        │
        │  backend:8000/metrics ──┐              │
        │  exporter.unix ─────────┤              │
        │  exporter.cadvisor ─────┤              │
        │                         ▼              │
        │        alloy ── logs ──────► 3100      │
        │           └──── metrics ────► 9090     │
        └───────────────────────────┬────────────┘
                                    │ VPC 내부 DNS
        ┌───────────────────────────▼────────────┐
        │ ops VM  judozoo-ops-prod  (외부 IP 없음)│
        │   loki:3100                            │
        │   prometheus:9090  ◄── remote_write    │
        │   grafana:3000 ──► Loki · Prometheus   │
        └────────────────────────────────────────┘
```

### 비용

**추가 $0.** VM도 디스크도 늘지 않는다 — ops VM에 컨테이너 하나가 더 뜰 뿐이다. [019](019-관측-vm-분리.md) 기준 월 ~$73 그대로이고 상한 $120에 여유가 남는다.

---

## 1. 백엔드 — `/metrics` 노출

- [x] `backend/pyproject.toml` — `prometheus-fastapi-instrumentator>=7.0` 추가 (`prometheus-client`는 전이 의존성으로 딸려온다)
- [x] `main.py` — 미들웨어 등록이 끝난 **뒤**에 계측을 붙인다. 나중에 등록한 미들웨어가 바깥에 서므로, 여기 붙여야 세션·인증 관문까지 포함한 전체 시간이 측정된다
      ```python
      Instrumentator(
          should_group_untemplated=True,          # 404 난사가 handler 라벨을 폭발시키지 않게
          excluded_handlers=["/health", "/health/db", "/metrics"],
      ).instrument(app).expose(app, include_in_schema=False)
      ```
- [x] `/health` 제외 확인 — compose 헬스체크가 10초마다 때리는 경로다. 넣어두면 요청 히스토그램이 헬스체크로 뒤덮인다

> **`/metrics`는 `_require_login` 관문 대상이 아니다.** 그 미들웨어는 `/api`로 시작하는 경로만 막는다. 지금 안전한 이유는 인증이 아니라 **nginx가 `/api/`만 프록시하고 backend에 호스트 포트가 없다**는 것 하나뿐이다. §7에 이 전제를 검증 항목으로 박아둔다.

## 2. 스케줄러·브로커 커스텀 메트릭

계측 대상 둘 다 `library/`에 있으므로 정의도 `library/metrics.py`에 둔다. 이름은 도메인 어휘를 피해 일반 명칭으로 — `library`는 비즈니스 로직을 담지 않는다.

- [x] `library/metrics.py` 신설
      ```python
      SCHEDULER_RUNS      = Counter("scheduler_job_runs_total", ..., ["job_id", "result"])
      SCHEDULER_ERRORS    = Counter("scheduler_job_errors_total", ..., ["job_id"])
      SCHEDULER_DURATION  = Histogram("scheduler_job_duration_seconds", ..., ["job_id"])
      HTTP_CLIENT_TOTAL   = Counter("http_client_requests_total", ..., ["vendor", "endpoint", "status"])
      HTTP_CLIENT_DURATION= Histogram("http_client_request_duration_seconds", ..., ["vendor", "endpoint"])
      ```
      **`job`이 아니라 `job_id`다** — 아래 함정 참고. 히스토그램 버킷도 기본값을 쓰지 않는다:
      브로커는 리미터 대기까지 포함해 20~30초가 정상이고 잡은 분 단위로 도는데, 기본 버킷은
      상한이 10초라 그 구간이 통째로 `+Inf`에 뭉친다
- [x] `library/scheduler.py` — `start()`에서 리스너 하나 등록. 잡마다 데코레이터를 다는 것보다 낫다 — 새 잡을 추가할 때 빠뜨릴 데가 없다
      ```python
      from apscheduler.events import EVENT_JOB_ERROR, EVENT_JOB_EXECUTED, EVENT_JOB_MISSED
      _scheduler.add_listener(metrics.on_job_event,
                              EVENT_JOB_EXECUTED | EVENT_JOB_ERROR | EVENT_JOB_MISSED)
      ```
      `EVENT_JOB_MISSED`를 꼭 포함한다. 미스파이어는 폴러가 밀리고 있다는 뜻이고, 로그에는 안 남는다
- [x] `library/metrics.py` — `MeteredTransport(httpx.BaseTransport)`. KIS의 `RateLimitedTransport`와 같은 모양이라 새 개념이 아니다. **event_hook이 아니라 transport인 이유**는 타임아웃·연결 실패를 잡아야 하기 때문이다 — hook은 응답이 온 경우만 본다
- [x] vendor 4곳의 `get_client()`에서 transport를 감싼다 — `kis` · `kiwoom` · `toss` · `yahoo`. KIS는 `MeteredTransport(RateLimitedTransport(...))` 중첩이 된다
- [x] `leadingstock/scheduler.py`의 `except` 블록에 실패 카운터를 직접 올린다 ← **§알려진 함정 참고. 리스너만으로는 안 잡힌다**
- [x] 테스트 — `tests/library/test_metrics.py`. 도메인 레이어가 아니므로 무겁지 않게, `MeteredTransport`가 응답/타임아웃 양쪽에서 라벨을 맞게 붙이는지만 respx로 본다

## 3. alloy — 스크레이프 + remote_write

- [x] `observability/alloy/config.alloy`에 메트릭 블록 추가 (기존 로그 블록은 그대로)
      ```alloy
      prometheus.scrape "backend" {
        targets         = [{ __address__ = "backend:8000" }]
        job_name        = "backend"
        metrics_path    = "/metrics"
        scrape_interval = "30s"
        forward_to      = [prometheus.remote_write.ops.receiver]
      }

      prometheus.exporter.unix "host" {
        procfs_path = "/host/proc"
        sysfs_path  = "/sys"
        rootfs_path = "/rootfs"
      }

      prometheus.scrape "host" {
        targets         = prometheus.exporter.unix.host.targets
        job_name        = "node"
        scrape_interval = "30s"
        forward_to      = [prometheus.remote_write.ops.receiver]
      }

      prometheus.remote_write "ops" {
        endpoint {
          url = "http://judozoo-ops-prod.asia-northeast3-a.c.trading-496508.internal:9090/api/v1/write"
        }
        external_labels = { host = "judozoo-server-prod" }
      }
      ```
      `job_name`을 따로 준다 — 타깃 맵에 `job`을 넣으면 예약 라벨과 섞인다.
      **cadvisor는 없다.** 배포 후 제거했다(아래 함정)
- [x] `docker-compose.yml`의 `alloy`에 마운트 추가 — **exporter가 호스트를 읽으려면 필요하다.** 지금은 docker 소켓 하나뿐이다
      ```yaml
      - /:/rootfs:ro
      - /sys:/sys:ro
      - /proc:/host/proc:ro          # 컨테이너 자기 /proc을 덮으면 안 된다
      ```
      `config.alloy`의 `procfs_path`·`sysfs_path`·`rootfs_path`와 **짝이다**
- [x] alloy 메모리 재측정 — 019 기준 95MB였다. exporter 둘이 붙으면 늘어난다. 앱 VM 4GB에 여유는 충분하지만 숫자는 남긴다

> **URL이 틀려도 에러로 드러나지 않는다.** 019에서 Loki push에 적어둔 함정이 그대로 반복된다 — 앱은 멀쩡히 돌고 Grafana 패널만 빈다.

## 4. ops VM — Prometheus 서비스

- [x] `observability/prometheus/prometheus.yml` 신설
      ```yaml
      global:
        scrape_interval: 30s
      scrape_configs:
        - job_name: prometheus          # 자기 자신만 스크레이프한다.
          static_configs:               # 앱 메트릭은 remote_write로 들어온다
            - targets: ["localhost:9090"]
      storage:
        tsdb:
          out_of_order_time_window: 30m   # ← 아래 함정 참고
      ```
- [x] `docker-compose.ops.yml` — `prometheus` 서비스 추가
      - 이미지 태그는 **정확한 패치 버전으로 고정**한다 (`prom/prometheus:v3.x` — loki `3.4.2` · grafana `11.5.2`와 같은 원칙)
      - `ports: - "9090:9090"` — alloy가 **다른 머신에서** 붙으므로 호스트에 내야 한다. Loki 3100과 같은 이유다
      - command:
        ```
        --config.file=/etc/prometheus/prometheus.yml
        --storage.tsdb.path=/prometheus
        --storage.tsdb.retention.time=30d
        --storage.tsdb.retention.size=4GB
        --web.enable-remote-write-receiver
        ```
      - `--web.enable-remote-write-receiver` **없으면 alloy의 push가 404로 조용히 버려진다**
      - 볼륨 `prometheus-data:/prometheus`, 설정은 읽기전용 바인드
- [x] `docker-compose.ops.prod.yml` — `restart: unless-stopped`
- [x] 컨테이너 uid 확인 — Prometheus 이미지는 `nobody`(65534)로 돈다. 명명 볼륨이면 docker가 알아서 맞춰주지만 바인드로 바꾸면 깨진다

## 5. 방화벽

- [x] `main.tf` — `judozoo-ops-allow-prom-write` 신설
      - `allow tcp:9090`, `source_tags = ["auto-trading"]`, `target_tags = ["judozoo-ops"]`
      - 기존 `judozoo-ops-allow-loki`에 포트를 끼워 넣지 않는다 — 이름이 거짓말이 된다
- [x] `terraform apply` — **배포 파이프라인은 terraform을 돌리지 않는다.** 직접 apply해야 한다

> `default-allow-internal`이 VPC 내부 전 포트를 이미 열어둔다. 이 규칙은 019의 `allow-loki`와 마찬가지로 **문서로서의 가치**지 실제 경계가 아니다.

## 6. Grafana

- [x] `provisioning/datasources/prometheus.yaml` 신설 — `url: http://prometheus:9090`, `uid: prometheus`
      ops VM 안에서는 같은 compose 네트워크라 서비스명이 그대로 맞는다 (loki.yaml과 동일한 구조)
- [x] **`isDefault`는 Loki에 그대로 둔다.** 옮길 이유가 없고, 기존 대시보드가 기본 데이터소스를 암묵적으로 쓰고 있으면 조용히 깨진다
- [x] 대시보드 1장 — `observability/grafana/dashboards/metrics.json` (패널 13개, 쿼리 14개)
      - backend: 요청률 · p95 지연 · 5xx 비율
      - 스케줄러: 잡별 실행 성공/실패률 · 미스파이어
      - 브로커: vendor별 호출률 · 에러율 · p95
      - 호스트/컨테이너: CPU · 메모리 · 디스크 여유
      `allowUiUpdates: true`라 UI에서 고쳐 export해도 된다. 초안은 손으로 썼고, 14개 쿼리를 전부
      실제 Prometheus에 걸어 결과가 나오는 것까지 확인했다(아래 표)

## 7. 배포 파이프라인

- [x] `remote_deploy_ops.sh` — 헬스체크에 Prometheus 추가: `curl -fsS localhost:9090/-/ready`
- [x] `remote_deploy_ops.sh` — Prometheus는 시크릿이 없다. `secrets/.env`에 더할 값이 없다
- [x] `deploy.yml` — `deploy-ops`의 scp 대상에 변화 없음. `observability/` 전체를 이미 보내므로 `prometheus/`가 따라간다
- [x] `remote_deploy.sh`(앱) — alloy `--force-recreate` 대상에 이미 포함. 설정 변경이 반영된다
- [x] `remote_deploy.sh`(앱) — **alloy 생존 검사 추가.** 기존 검사는 backend·프론트뿐이라, alloy가 죽어도 배포가 초록으로 끝난다. 위 함정의 유일한 방어선이다
- [x] 배포 순서 확인 — `deploy-ops` → `deploy`. Prometheus가 먼저 서 있어야 alloy가 remote_write 재시도를 안 겪는다 (Loki와 같은 이유)

## 8. Slack 알림 — ②번 패널

**대시보드 13개 패널 중 여기만 알림을 붙인다.** 나머지는 상태를 보여주지만 이 패널만
"지금 뭔가 잘못됐다"를 보여준다 — 평소 완전히 비어 있는 게 정상인 유일한 패널이다.
Alertmanager는 세우지 않는다(설계 결정 표 참고). Grafana 알림이 같은 데이터소스를 그대로 쓴다.

- [x] `provisioning/alerting/rules.yaml` — 규칙 2개. **계열이 둘이고 뜻이 달라서 나눈다**
      - `스케줄러 잡이 실패를 삼키고 있다` — `scheduler_job_errors_total` (예외로 깨짐)
      - `스케줄러 잡이 밀리고 있다` — `result="missed"` (이전 실행이 안 끝나 건너뜀)
- [x] 문턱 **10분 내 3회 초과 + `for: 2m`**. 0으로 두지 않는 이유는 브로커 네트워크가
      한 번 튀는 것으로 울리면 알림을 끄게 되기 때문이다. 10초 폴러가 진짜 깨지면 10분에
      60번 실패하므로 3은 여전히 낮은 문턱이다
- [x] `noDataState: OK` — 배포 중 backend가 잠깐 사라져 시계열이 끊기는 것으로 울리지 않는다
- [x] `provisioning/alerting/contact-points.yaml` — Slack. URL은 `$SLACK_WEBHOOK_URL`
      (`datasources/mysql.yaml`의 `$DB_HOST`와 같은 방식). **저장소에 URL이 없다**
- [x] `provisioning/alerting/notification-policies.yaml` — 기본 라우트를 Slack으로,
      `group_by: [alertname, job_id]`, `repeat_interval: 4h`
- [x] `remote_deploy_ops.sh` — `AT_GRAFANA_SLACK_WEBHOOK_URL`을 Secret Manager에서 받는다.
      **비어 있으면 배포를 끊는다** — 히어독 안의 `$(fetch ...)` 실패는 `set -e`에 안 걸려
      빈 값으로 넘어가고, 그러면 알림이 발송되지 않는데 아무 소리도 안 난다
- [x] 마운트 검사에 `alerting/` 파일 추가 (§7의 stale bind-mount 방어)
- [ ] **`AT_GRAFANA_SLACK_WEBHOOK_URL` 시크릿 생성** ← 사람이 해야 한다. 이게 없으면 배포가 실패한다
      ```
      gcloud secrets create AT_GRAFANA_SLACK_WEBHOOK_URL --data-file=- <<< '<웹훅 URL>'
      ```
- [ ] 실제 Slack 채널에 메시지가 도착하는지 확인

### 로컬 검증 (2026-09-17)

| 항목 | 결과 |
|---|---|
| 프로비저닝 | Grafana 11.5.2 실기동 — `finished to provision alerting`, 에러 0건 |
| 규칙 등록 | 2개 모두 등록. `for=2m`, `noData=OK`, threshold `gt 2` |
| 규칙 평가 | 둘 다 `inactive` — 쿼리가 에러 없이 돌고 문턱 미달 상태 |
| 수신처 | `$SLACK_WEBHOOK_URL` 확장 확인. 기본 라우트가 slack |
| 전송 배선 | 더미 URL로 테스트 → `failed incoming webhook: no_team`. **Slack이 직접 거부한 응답**이라 요청이 제대로 나갔다는 뜻이다 |

## 검증

### 로컬에서 확인한 것 (2026-09-17, 배포 전)

| 대상 | 결과 |
|---|---|
| `/metrics` 응답 | 200, `http_requests_total` 포함 |
| `/health` 제외 | 히스토그램에 안 잡힘 |
| 백엔드 테스트 | `543 passed` — vendor 4곳에 transport를 끼워도 기존 respx 테스트 전부 통과 |
| `config.alloy` 파싱 | `alloy fmt` 통과 |
| alloy 컴포넌트 인자 | v1.7.5 실기동 — `job_name`·`procfs_path`·`external_labels`·`docker_host` 모두 수용 |
| `prometheus.yml` | `promtool check config` SUCCESS |
| Prometheus 기동 | `/-/ready` 200, `up{job="prometheus"}` 존재 |
| remote_write 수신구 | `POST /api/v1/write` → **400**(404 아님) = 수신 활성 |
| Grafana 프로비저닝 | 11.5.2 실기동 — 데이터소스 3개(Loki 기본 유지·Prometheus·MySQL), 대시보드 3장 로드 |
| **end-to-end** | 실제 `/metrics` 본문 → alloy scrape → remote_write → Prometheus 질의까지 흘렀다 |
| 대시보드 쿼리 | 14개 전부 실행. 스케줄러·브로커·API 패널이 의도한 라벨로 분리돼 나옴 |

### 배포 후 실측 (2026-09-17)

| 항목 | 결과 |
|---|---|
| ops VM | `prometheus` Up, `/-/ready` OK. **41MB** — loki 95 + grafana 125 + prometheus 41 = 261MB / 1.9GB. e2-small 유지 판단이 맞았다 |
| `up` 타깃 | `backend` · `prometheus` · `integrations/unix` 모두 1 |
| 스케줄러 | `job_id` 라벨로 분리돼 들어옴(`signal-event-poller` · `index-rebound-poller`). `exported_job` 없음 |
| 브로커 | `kis` · `toss` 확인. **`kiwoom`·`yahoo`는 장중에 재확인** — 마감 후라 아직 호출이 없다 |
| 호스트 | 메모리 여유 3.2GB, 디스크 여유 15.3GB |
| backend 자체 | `process_resident_memory_bytes` **131MB** (019 실측 172MB) |
| alloy 메모리 | **83.9MB** — exporter가 붙었는데도 019의 95MB보다 줄었다 |
| `/metrics` 격리 | 호스트에서 `localhost:8000` 도달 불가(000). 컨테이너 안에서만 17,627바이트 |
| cadvisor | **실패 → 제거.** 아래 함정 |
| 재배포 후 | cadvisor 에러 0건, alloy 83.9 → **60.4MB**. cadvisor 시계열은 staleness(5분) 뒤 사라진다 |
| backend 재시작 | 재배포로 backend가 교체됐지만 **그 이전 데이터가 남아 있다** (12분 전 샘플 조회됨) |
| TSDB | 612K / 약 15분치. 다만 cadvisor 2천 시계열이 섞인 값이라 24시간 뒤 다시 잰다 |
| Grafana | 3차 배포에서 해결. 데이터소스 `Prometheus` 등록, 대시보드 3장 프로비저닝, 에러 0건 |
| 브로커 vendor | **4곳 전부 확인** — kis 6 · kiwoom 6 · toss 3 · yahoo 1 endpoint |
| endpoint 카디널리티 | 총 16개. 야후가 **정확히 1개**라 심볼 정규화가 먹었다 |
| 대시보드 쿼리 | 운영 데이터로 14개 실행 — 패널 대부분 값이 나온다 |

### 아직 확인 못 한 것


- [x] 백엔드 컨테이너 안에서 `curl localhost:8000/metrics`가 답한다
- [x] **밖에서 메트릭이 안 나온다** — 단 `/metrics`는 **404가 아니라 200**이다. nginx SPA fallback이
      `index.html`을 준다(`content-type: text/html`, 본문에 메트릭 문자열 0건). `/api/metrics`는 401.
      **상태코드로 판정하면 안 되는 확인이다** — 본문을 봐야 한다
- [x] 앱 VM 호스트에서 `curl localhost:8000/metrics` **실패** (호스트 포트가 없어야 한다)
- [x] ops VM `curl localhost:9090/-/ready` OK
- [x] Prometheus `/api/v1/query?query=up` 에 `job="backend"` 시계열이 있다
- [x] `scheduler_job_runs_total`이 장중에 증가한다 — 폴러 주기(10s/30s)와 맞는 기울기인지 본다
- [x] `http_client_requests_total`에 vendor 4곳이 모두 나타난다. **안 나타나는 vendor가 §2 편집을 빠뜨린 곳이다**
- [x] ~~cadvisor 메트릭에 컨테이너 4개가 보인다~~ — **불가로 판명해 항목 폐기.** 아래 함정
- [x] Grafana에 Prometheus 데이터소스와 메트릭 대시보드가 올라왔다 (3차 배포).
      **패널 육안 확인은 남았다** — IAP 터널로 접속해서 본다
- [x] **backend를 재시작**해도 Prometheus의 과거 데이터가 남아 있다 — 재배포가 backend를 교체했는데 그 이전 샘플이 조회됐다
- [ ] ops VM 재부팅 후 prometheus 자동 복귀 + 볼륨 데이터 보존
- [ ] 24시간 뒤 `du -sh` — TSDB 실크기를 재고 30일 추정치를 다시 계산한다
- [x] `terraform plan` — 방화벽 apply 후 `0 to add, 1 to change, 0 to destroy`. 남은 1건은 019가 "늘 뜬다"고 적어둔 Artifact Registry drift다

## 알려진 함정

- **ops 배포에 `--force-recreate`가 없으면 Grafana 프로비저닝이 조용히 죽는다.** `deploy.yml`은 매 배포마다 `sudo rm -rf ~/observability` 후 재-scp한다. 호스트 디렉토리의 inode가 교체되는데, compose는 설정이 안 바뀐 서비스를 **재사용**하므로 그 컨테이너는 사라진 inode를 계속 가리킨다. 컨테이너 안에서는 빈 디렉토리로 보인다:
      ```
      level=error msg="failed to search for dashboards"
        error="readdirent /var/lib/grafana/dashboards: no such file or directory"
      ```
      **아무것도 실패하지 않는다.** grafana는 정상 기동하고 `/api/health`도 `ok`를 주고 배포는 초록이다. 이미 DB에 들어간 데이터소스·대시보드는 계속 보이므로 화면으로도 모른다. 020에서 Prometheus 데이터소스와 메트릭 대시보드가 **두 번의 배포 동안 한 번도 올라가지 못했다** — 컨테이너 생성 시각(`docker inspect -f {{.Created}}`)을 prometheus(신규)와 비교하고서야 드러났다.
      앱 VM의 `remote_deploy.sh`는 alloy에 대해 **이미 같은 조치를 하고 있었다.** ops 쪽에만 없었다. `--force-recreate`를 넣고, 마운트가 실제로 보이는지 검사하는 단계를 붙였다.
- **APScheduler 리스너는 "삼켜진 예외"를 성공으로 본다.** `poll_signal_events`는 내부에서 `except: log.warning(...)` 후 정상 리턴한다. 스케줄러 입장에서는 성공이다 — **이 작업이 답하려는 첫 번째 질문이 바로 여기인데, 리스너만 달면 정확히 그것만 못 잡는다.** catch 지점에서 카운터를 직접 올려야 한다.
- **cadvisor는 이 호스트에서 도커 컨테이너를 못 본다.** Docker가 containerd 이미지 스토어를 쓴다(`docker info` → storage driver **`overlayfs`**). 그러면 `/var/lib/docker/image/<driver>/layerdb/`가 아예 없고, cadvisor는 컨테이너마다 이렇게 실패한다:
      ```
      Failed to create existing container: /system.slice/docker-<id>.scope:
        failed to identify the read-write layer ID ... no such file or directory
      ```
      증상이 고약하다 — **스크레이프 타깃은 `up=1`이고 시계열도 2천 개 넘게 들어온다.** 전부 systemd 유닛(chrony·cron·containerd) 것이고 `name` 라벨이 없어 도커 컨테이너는 하나도 없다. 게다가 이 에러가 분당 alloy stdout에 쌓여 **Loki까지 오염시킨다.** 마운트로 고칠 수 있는 문제가 아니라서 걷어냈다.
      대신 `process_resident_memory_bytes{job="backend"}`를 쓴다 — 원래 답하려던 "backend 메모리가 새고 있나"에 직접 답하고, 이미 들어와 있다.
- **`https://<도메인>/metrics`는 200을 준다.** 메트릭이 새는 게 아니라 nginx SPA fallback의 `index.html`이다. 상태코드만 보고 "노출됐다"고 판단하면 안 되고, 반대로 200을 보고 안심해서도 안 된다 — **본문을 봐야 한다.**
- **내장 exporter의 `instance`는 alloy 컨테이너 ID다.** 배포할 때마다 바뀌므로 호스트 시계열이 매번 새로 갈라지고, 옛 조각은 stale로 남는다. 머신은 하나인데 30일 보존이면 배포 횟수만큼 조각이 쌓인다. 실제로 배포 두 번 만에 `node_memory_MemAvailable_bytes`가 2벌, 디스크가 mountpoint마다 2벌이 됐다. `discovery.relabel`로 `instance`를 호스트 이름에 고정했고, 대시보드도 `max by (host)`로 묶어 과거 조각이 여러 줄로 보이지 않게 했다.
- **`job`은 라벨 이름으로 쓸 수 없다.** 처음엔 `scheduler_job_runs_total{job="signal-event-poller"}`로 만들었는데, `job`·`instance`는 Prometheus가 스크레이프 타깃에 붙이는 예약 라벨이라 충돌하면 우리 쪽이 `exported_job`으로 밀려난다. 로컬에서 실제로 흘려보고서야 드러났다:
      ```
      {job="backend", exported_job="signal-event-poller", result="success"}
      ```
      그대로 뒀다면 `sum by (job)`이 잡 5개를 전부 `"backend"` 한 줄로 뭉쳐 대시보드가 무의미해진다. **에러는 어디에도 안 난다.** `job_id`로 바꿨다.
- **remote_write 수신은 기본으로 꺼져 있다.** `--web.enable-remote-write-receiver` 없이 뜨면 alloy push가 404로 버려지고, alloy 로그를 들여다보기 전까지는 대시보드가 빈 것만 보인다.
- **순서 어긋난 샘플은 기본적으로 거부된다.** Prometheus는 뒤늦게 도착한 과거 시각 샘플을 버린다. ops VM이 잠깐 죽었다 살아나면 alloy WAL이 밀린 샘플을 재전송하는데, `out_of_order_time_window`가 없으면 그 구간이 통째로 사라진다. 019에서 alloy WAL 복귀를 확인해둔 것과 짝이 되는 설정이다.
- **`/metrics`를 지키는 건 인증이 아니라 라우팅이다.** `_require_login`은 `/api`만 본다. 누군가 nginx에 `location /metrics`를 추가하는 순간 인터넷에 열린다.
- **uvicorn에 `--workers`를 붙이면 `/metrics`가 깨진다.** 워커마다 레지스트리가 따로라 스크레이프할 때마다 다른 워커의 숫자가 나온다. 고치려면 `PROMETHEUS_MULTIPROC_DIR`가 필요하다. **지금 단일 프로세스인 것이 전제다.**
- **handler 라벨 카디널리티.** 라우트 템플릿 단위라 안전하지만, 매칭 안 되는 경로(봇의 `/wp-login.php` 난사)가 그대로 라벨이 되면 시계열이 폭발한다. `should_group_untemplated=True`가 그걸 막는다.
- **마운트를 빠뜨리면 alloy가 통째로 죽는다 — 로그까지 같이 멈춘다.** 처음에는 "exporter만 조용히 빈다"고 적었는데, v1.7.5를 실제로 띄워보니 아니었다. `/host/proc`이 없자 설정 평가 단계에서 전체가 **exit 1**로 내려갔다:
      ```
      level=error msg="failed to evaluate config" node=prometheus.exporter.unix.host
        err="failed to open procfs: could not read \"/host/proc\""
      ```
      메트릭 마운트 실수 하나가 019에서 세운 로그 파이프라인을 끊는다는 뜻이다. 설정 오류 전반이 같게 동작한다 — `scrape_interval`을 기본 `scrape_timeout`(10초)보다 짧게 주는 것만으로도 죽는다. 그래서 `remote_deploy.sh`에 alloy 생존 검사를 넣었다(§7) — 없으면 배포는 초록인데 로그만 사라진다.
- **`endpoint` 라벨에 쿼리스트링을 넣지 않는다.** KIS·키움은 경로 하나에 TR-ID 헤더로 기능을 나누므로 경로 기준 카디널리티는 낮지만, 종목코드가 쿼리로 붙는 vendor가 있으면 종목 수만큼 시계열이 생긴다.
- **Prometheus는 UTC로 저장한다.** 다른 컨테이너처럼 `TZ: Asia/Seoul`을 넣어도 TSDB는 안 바뀐다 — 표시는 Grafana가 맡는다. 019에서 grafana에 TZ를 박은 것과 성격이 다르다.
- **보존 상한 두 개는 OR이다.** 30일과 4GB 중 먼저 닿는 쪽에서 오래된 블록이 삭제된다. 카디널리티가 예상보다 크면 30일을 못 채운다.

## 후속

- **Tempo(트레이스).** 스케줄러 잡 하나가 브로커 API 여러 개로 팬아웃하는 구조라 스팬 트리가 실제로 값어치를 한다. 다만 자동 계측이 HTTP 요청 경로만 덮으므로 **잡 경계 스팬은 직접 열어야 하고, 정작 중요한 게 그 경로다**. 로그 포맷에 `trace_id`를 넣어야 Loki↔Tempo 점프가 산다. 그때 ops VM 메모리를 다시 잰다
- Grafana 알림 — `scheduler_job_runs_total{result="error"}` 급증, 브로커 에러율, 디스크 여유. Alertmanager 없이 Grafana 쪽에서 붙인다
- 레이트 리미터 대기 시간 메트릭 — `RateLimiter`에서 최대 20초 대기가 발생한다(`frontend/nginx.conf` 주석 참고). 429의 선행 지표가 된다
- Prometheus 보존 재검토 — 019에서 Loki 7일이 17MB로 밝혀져 늘릴 여지가 생겼듯, 실측 후 조정한다
