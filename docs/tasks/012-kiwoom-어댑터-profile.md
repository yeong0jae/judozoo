# 012 — Phase 10: Kiwoom 어댑터 + Profile 와이어링

Goal: Phase 9가 만든 `BrokerTradingClient` 자리에 Kiwoom 어댑터를 끼우고, broker × env profile로 인스턴스별 선택 가능하게 한다.

> 10-A → 10-B 두 단계 분할 (위험 격리). 10-A는 KIS 동작 그대로 유지하며 자리만 만든다. 10-B에서 Kiwoom 실구현.

---

## 설계 원칙

- **broker(`kis`|`kiwoom`)와 env(`vts`|`real`)는 직교 profile**로 분리. 인스턴스마다 둘 중 정확히 1개씩 active.
  - 유효 조합: `kis,vts` / `kis,real` / `kiwoom,real`. `kiwoom,vts`는 부팅 거부(Kiwoom 모의 미운영).
- **KIS 인프라 빈(`KisRestClient` 등)은 이번 phase에서 게이팅하지 않는다.** 다른 feature(market/report/account/stock)가 직접 의존하기 때문. broker 어댑터 선택만 게이팅.
- **fail-fast.** 잘못된 profile 조합으로 띄우면 컨텍스트 시작 자체가 실패해야 함 — 런타임에서 발견되면 안 됨.

---

## Phase 10-A: Profile 와이어링 + Kiwoom stub

### 작업

- [x] `KisBrokerAdapter`에 `@Profile("kis")` 추가
- [x] `platform.kiwoom.adapter.KiwoomBrokerAdapter` 신설 — `@Profile("kiwoom")` + 모든 메서드 `NotImplementedError` throw
- [x] `application.broker.BrokerActivationGuard` 신설 — `@PostConstruct`로 broker·env 조합 검증 + test profile 면제
- [x] `application-kiwoom.yaml` placeholder 생성
- [x] `IntegrationTestBase`의 `@ActiveProfiles("test")` → `@ActiveProfiles("test", "kis")`
- [x] default profile을 `kis,vts`로 갱신 (application.yaml + docker-compose.yml + remote_deploy.sh)

### Verification

- [x] `./gradlew test` 전체 green (1m 25s)
- [x] 로컬 `docker compose up backend` 부팅 4초, 로그 `The following 2 profiles are active: "kis", "vts"`

### DoD

- 기존 KIS-vts 인스턴스 (`kis,vts`) 동작 무변화
- Kiwoom 어댑터 자리만 존재, 호출은 즉시 `NotImplementedError`
- profile guard가 잘못된 조합을 컨텍스트 시작 단계에서 차단

---

## Phase 10-B: Kiwoom 트레이딩 클라이언트 구현

확보된 Kiwoom api-id 매핑:
- currentPrice / searchStock → `ka10001` (stkinfo)
- availableCash → `kt00004` (acnt, `d2_entra`)
- placeOrder BUY → `kt10000` (ordr)
- placeOrder SELL → `kt10001` (ordr)
- cancelOrder → `kt10003` (ordr, `cncl_qty:'0'` = 잔량 전부)
- executionNotices → WebSocket `wss://api.kiwoom.com:10000/api/dostk/websocket` type `00` (주문체결)
- isMarketOpen → Kiwoom API 없음 → 평일(Mon~Fri) 단순 체크로 사전 차단, 공휴일은 거부 응답에 위임

### 작업

- [x] `platform.kiwoom.config.KiwoomTradingProperties` 신설 — `kiwoom.trading.account-no/ws-url/dmst-stex-tp`
- [x] `application-kiwoom.yaml`에 Kiwoom trading 매핑 추가
- [x] `platform.kiwoom.client.KiwoomTradingClient` 신설 (REST): ka10001/kt00004/kt10000/kt10001/kt10003
- [x] `platform.kiwoom.client.KiwoomExecutionWebSocketClient` 신설 — LOGIN→REG type=00, 평문 JSON, PINGPONG echo, 백오프 재연결
- [x] `KiwoomBrokerAdapter` 실구현으로 교체 (stub → 실제 위임)
- [x] `BrokerTradingClient.cancelOrder` 시그니처에 `stockCode` 추가 (Kiwoom kt10003은 stk_cd 필수), KIS·OrderService·CycleOrchestrator 일괄 수정
- [ ] WireMock 인프라 테스트 — 성공·거부·timeout 시나리오
- [ ] 로컬 `SPRING_PROFILES_ACTIVE=kiwoom,real` 부팅 smoke (Kiwoom 콘솔 IP 화이트리스트 + KIWOOM_ACCOUNT_NO 필요)
- [ ] Secret Manager: `AT_KIWOOM_ACCOUNT_NO` 추가 (Phase 11 배포 시점에)

### Verification

- [x] 기존 KIS-vts 테스트 회귀 0건 (`./gradlew test` 1m 15s green)
- [ ] WireMock 테스트 전 시나리오 green (후속)
- [ ] 로컬 Kiwoom-real 부팅 성공 (후속)

### DoD

- 3개 profile 조합 모두 부팅 가능 (KIS-vts/real 변화 없음, Kiwoom-real 신규)
- Kiwoom 트레이딩 API가 `BrokerTradingClient` 인터페이스 뒤에서 동작

---

## 후속 / Phase 11 (013)

- Terraform `for_each = toset(["kis-vts","kis-real","kiwoom-real"])` 변환 + state mv
- GHA matrix 3-entry
- 3 VM 동시 배포 + 헬스체크
