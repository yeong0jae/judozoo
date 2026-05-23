# tasks-012 — Phase 10: Kiwoom 어댑터 + Profile 와이어링

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

> 사용자 입력 필요: Kiwoom OpenAPI 주문/잔고/취소/체결 통지의 정확한 api-id와 요청·응답 스펙

### 작업

- [ ] `platform.kiwoom.config.KiwoomTradingProperties` 신설 — Kiwoom 계좌 정보(accountNo 등)
- [ ] `application-kiwoom.yaml`에 Kiwoom trading creds 매핑 추가
- [ ] Secret Manager: `AT_KIWOOM_ACCOUNT_NO` 등 누락 시크릿 추가
- [ ] `platform.kiwoom.client.KiwoomTradingClient` 신설 (REST):
  - 현재가 / 잔고 / 종목 검색 / 휴장 / 주문 발주 / 주문 취소
- [ ] `platform.kiwoom.client.KiwoomExecutionNoticeClient` 신설 — 실시간 체결 통지 (WebSocket 또는 polling)
- [ ] WireMock 인프라 테스트 — 성공·거부·timeout 시나리오
- [ ] `KiwoomBrokerAdapter` 실구현으로 교체 (stub → 실제 위임)
- [ ] 로컬 `SPRING_PROFILES_ACTIVE=kiwoom,real` 부팅 — access token 발급 + 종목 검색·잔고 조회 smoke

### Verification

- [ ] WireMock 테스트 전 시나리오 green
- [ ] 로컬 Kiwoom-real 부팅 성공 (Kiwoom 콘솔 IP 화이트리스트 전제)
- [ ] 기존 KIS-vts 테스트 회귀 0건

### DoD

- 3개 profile 조합 모두 부팅 가능 (KIS-vts/real 변화 없음, Kiwoom-real 신규)
- Kiwoom 트레이딩 API가 `BrokerTradingClient` 인터페이스 뒤에서 동작

### Open Questions (사용자 확인 필요)

- Kiwoom 주문 발주 api-id (ka????)
- Kiwoom 잔고 조회 api-id
- Kiwoom 주문 취소 api-id
- Kiwoom 체결 통지 — REST polling 가능? WebSocket 스펙?
- Kiwoom에서 종목명 검색 — `ka10001` 재사용? 다른 api-id?
- Kiwoom 휴장 여부 조회 api-id

---

## 후속 / Phase 11 (tasks-013)

- Terraform `for_each = toset(["kis-vts","kis-real","kiwoom-real"])` 변환 + state mv
- GHA matrix 3-entry
- 3 VM 동시 배포 + 헬스체크
