# 006 — Phase 5-A: 백엔드 도메인 이벤트 + STOMP 브로드캐스트

Goal: 사이클 엔진의 핵심 분기점에서 도메인 이벤트를 발행하고, STOMP 토픽으로 브로드캐스트하는 인프라를 구축한다. 프론트(Phase 5-B)가 구독할 안정적 토픽 표면을 제공.

> Phase 4 DoD는 "broadcast hook 부착 위치 정렬"까지 명시했지만 실제 publisher는 미도입. 본 phase에서 publisher + STOMP 서버를 함께 만든다.
> 프론트 통합은 [007-ui-ux-재설계.md (Phase 5-B)](007-ui-ux-재설계.md) 로 분리.

---

## 토픽 표면 (spec §11.5)

```
/topic/trading/{id}        - PRICE / STATE / SIGNAL / EXECUTION / RETRY
/topic/trading/lifecycle   - CREATED / CLOSED
/topic/market              - MARKET_MODE / HOLIDAY
/topic/account             - BALANCE_INVALIDATED
```

`BALANCE_INVALIDATED`는 lifecycle `CREATED`/`CLOSED`와 함께 자동 발행.

분산 모델 — 별도 broadcast feature를 두지 않고 각 feature가 자기 broadcast handler를 가진다. STOMP 셋업만 cross-cutting (`library/web/WebSocketConfig`).

---

## 사전 정비

- [x] 도메인 이벤트 발행 hook 위치 식별 — 현 코드의 분기점들이 spec §11.5 토픽과 1:1 매핑되는지 점검 (필요 시 분기 보강)

## 도메인 이벤트 정의 + 발행

- [x] 이벤트 클래스 정의 — feature별 `domain/event/`:
  - **trading**: `TradingCycleCreated`, `TradingCycleClosed`, `PriceUpdated`, `CycleStateChanged`, `SignalArmed`, `SignalFired`, `OrderExecuted`, `RetryAccumulated`
  - **market**: `MarketModeChanged`, `HolidayChanged`
  - **account**: `BalanceInvalidated`
- [x] Spring `ApplicationEventPublisher`로 발행 — 위치:
  - `TradingService.create` afterCommit → `TradingCycleCreated` + `BalanceInvalidated`
  - Runner `finalizeBuySequence`/`processExternalSignal`/`checkAndCloseIfDone` close → `TradingCycleClosed` + `BalanceInvalidated` (+ `CycleStateChanged` CLOSED)
  - `UnclosedCycleStartupHook` → `TradingCycleClosed` + `BalanceInvalidated`
  - `ExecutionNoticeHandler` 체결 적용 → `OrderExecuted`
  - Runner `updateArming` → `SignalArmed`
  - Runner `executeSignalSell` 진입 → `SignalFired`
  - Runner `processTick` → `PriceUpdated`
  - Runner `runBuySequence`/`finalizeBuySequence`/`requestCancellation`/`executeSignalSell` → `CycleStateChanged`
  - `OrderExecutor.executeSell` retry → `RetryAccumulated`
  - `MarketDataStream` mode 변경 → `MarketModeChanged`
  - `TradingSchedulerService.toggleCommandGate` → `HolidayChanged`

## STOMP 인프라

- [x] `library/web/WebSocketConfig` — STOMP broker `/topic`, endpoint `/ws` 등록
- [x] CORS — `setAllowedOriginPatterns("*")`. 인증은 단일 사용자 환경이라 미적용
- [x] 페이로드 DTO (각 feature `presentation/payload/`):
  - `trading.presentation.payload.TradingPayload` 변종 (PRICE / STATE / SIGNAL / EXECUTION / RETRY)
  - `trading.presentation.payload.LifecyclePayload` (CREATED / CLOSED)
  - `market.presentation.payload.MarketModePayload`, `HolidayPayload`
  - `account.presentation.payload.BalanceInvalidatedPayload`

## Broadcast Handler (각 feature 분산)

- [x] `trading.application.TradingBroadcastHandler` — trading 이벤트 listen → `/topic/trading/{id}`, `/topic/trading/lifecycle`
- [x] `market.application.MarketBroadcastHandler` — market 이벤트 listen → `/topic/market`
- [x] `account.application.AccountBroadcastHandler` — `BalanceInvalidated` listen → `/topic/account`
- [x] CREATED/CLOSED 시 BalanceInvalidated 동반 발행 (publisher가 둘 다 publish)

## 통합 테스트 (testing.md 컨벤션 — application 레이어 통합만)

- [x] `TradingServiceBroadcastTest` (IntegrationTestBase + `MessagingTemplateMockConfig`):
  service.create → publishEvent → @EventListener listener → broadcaster → `messagingTemplate.convertAndSend`까지의 사슬을 1건 검증 (CREATED + BALANCE_INVALIDATED 동반 발행)

> wire 통합(STOMP 클라이언트 + JSON 직렬화)은 framework 신뢰 영역으로 제외.
> 페이로드 contract / 토픽 path는 broadcaster 단위 테스트에서 검증.

## Verification

- [x] `./gradlew test` 전체 통과
- [x] STOMP 페이로드가 spec §11.5 스키마와 1:1 일치 (필드명/타입은 broadcaster 단위 테스트에서)

---

## Definition of Done

- 위 통합 테스트 통과
- 프론트(Phase 5-B)가 토픽을 구독해 PRICE/STATE/SIGNAL/EXECUTION/RETRY/lifecycle/market/account 페이로드를 그대로 사용 가능
- `Holiday` 발행은 변경 hook이 시스템에 존재하면 발행, 없으면 후속 이슈로 분리

## 후속 / Phase 5-B (007)

- 프론트 STOMP 클라이언트 + 자동 재연결
- TanStack Query 도입 → mock 제거
- React Hook Form + Zod 폼 검증
- 모니터링/명령 화면의 토픽 구독 흐름 (spec §11.5)
- 종료 토스트, 시스템 상태 배지, BALANCE_INVALIDATED 처리
