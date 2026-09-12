# 011 — Phase 9: Broker 추상화

Goal: KIS 직접 호출을 `BrokerTradingClient` 인터페이스 뒤로 숨긴다. 이번 phase에서는 **행동을 1bit도 바꾸지 않는다** — Phase 10에서 Kiwoom 어댑터가 같은 인터페이스로 들어올 수 있는 자리만 만든다.

> 이번 phase의 가치는 "교체 지점이 한 군데로 모인다"는 것 한 줄. 실제 다중-브로커는 Phase 10·11에서 완성.

---

## 설계 원칙

- **행동 보존이 1순위.** 기존 통합 테스트 회귀 0건이 합격 기준.
- **인터페이스는 `trading.application.broker`에 둔다.** Architecture rule: cross-feature 호출은 application 사이만, 의존 방향은 platform → application.
- **어댑터는 얇게.** `KisRestClient`/`KisWebSocketClient` 위에서 단순 위임. 비즈니스 로직이 어댑터에 들어가면 잘못된 추상화 신호.
- **DTO는 trading.domain의 기존 값 객체 재사용.** 어댑터별 DTO를 새로 만들지 않는다.

---

## 사전 작업

- [x] 현재 main의 통합 테스트 전부 green 확인 (회귀 기준점)

## 인터페이스 도출

- [x] `trading.application.broker.BrokerTradingClient` 정의 — accountNo / currentPrice / availableCash / searchStock / isMarketOpen / placeOrder / cancelOrder + `executionNotices: SharedFlow<ExecutionNotice>` / subscribeExecutionNotices
- [x] 메서드 시그니처를 trading.domain 값 객체 기준으로 작성 — `PlacedOrder`, `StockInfo`, `BrokerOrderRejectedException` 신설, 기존 `OrderSide` / `ExecutionNotice`(domain) 재사용

## KisBrokerAdapter 구현

- [x] `platform.kis.adapter.KisBrokerAdapter : BrokerTradingClient` — `KisRestClient` / `KisRealQuotationClient` / `KisWebSocketClient` / `KisProperties` 주입, 응답 → 값 객체 변환, `KisOrderRejectedException` → `BrokerOrderRejectedException` 변환
- [x] 기존 `platform.kis.*`는 변경 금지 (이번 phase 범위 밖) — 유지

## 호출부 교체 (6개 파일)

- [x] `trading.application.OrderService` — KIS 직접 의존 제거, broker 의존, `isEgw00201`이 `BrokerOrderRejectedException.code` 검사
- [x] `trading.application.TradingService` — `KisProperties.accountNo` → `broker.accountNo`
- [x] `trading.application.TradingQueryService` — `kisRestClient.getCurrentPrice(...)` → `broker.currentPrice(...)`, accountNo 동일
- [x] `trading.application.TradingValidator` — searchStock / availableCash / isMarketOpen / currentPrice 전부 broker 위임
- [x] `trading.application.ExecutionNoticeListener` — `KisWebSocketClient` → broker.executionNotices + subscribeExecutionNotices
- [x] `trading.application.UnclosedCycleStartupHook` — `KisProperties` → broker.accountNo

## 테스트 영향

- [x] `TradingValidatorTest` — broker 모킹으로 재작성 (StockInfo / isMarketOpen / currentPrice / availableCash)
- [x] 통합 테스트 무수정 — `KisRestClientMockConfig`가 `@Primary`로 `KisRestClient`를 mock으로 주입 → `KisBrokerAdapter`가 그 mock에 위임 → 기존 stub 그대로 통과

## Verification

- [x] `grep -rln "platform.kis" backend/src/main/kotlin/at/backend/trading/` 결과 0건
- [x] `./gradlew test` 전체 green (1m 25s)
- [x] 통합 테스트 9개 (Phase 4 시나리오) 전부 green
- [ ] 로컬 `docker compose up` → `vts` profile 부팅, 모든 화면 동작 (수동 smoke)

## Definition of Done

- 위 Verification 통과
- `BrokerTradingClient`가 외부에 노출하는 표면이 domain 값 객체로만 구성됨 (KIS DTO 누수 0)
- Phase 10에서 `KiwoomBrokerAdapter`만 추가해 `@Profile`로 바꾸면 동작 가능한 구조

## 후속 / Phase 10 (012)

- `platform.kiwoom.trading.*` 신설 (REST 클라이언트 + 주문/잔고/체결 모듈)
- WireMock 인프라 테스트
- `KiwoomBrokerAdapter : BrokerTradingClient` 구현
- `application-{kis,kiwoom,vts,real}.yml` profile 분리
- broker·env active 검증 fail-fast `ApplicationRunner`
