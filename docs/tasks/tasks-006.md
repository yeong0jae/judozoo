# tasks-006 — Phase 5-A: 백엔드 도메인 이벤트 + STOMP 브로드캐스트

Goal: 사이클 엔진의 핵심 분기점에서 도메인 이벤트를 발행하고, STOMP 토픽으로 브로드캐스트하는 인프라를 구축한다. 프론트(Phase 5-B)가 구독할 안정적 토픽 표면을 제공.

> Phase 4 DoD는 "broadcast hook 부착 위치 정렬"까지 명시했지만 실제 publisher는 미도입. 본 phase에서 publisher + STOMP 서버를 함께 만든다.
> 프론트 통합은 [tasks-007.md (Phase 5-B)](tasks-007.md) 로 분리.

---

## 토픽 표면 (spec §11.5)

```
/topic/trading/{id}        - PRICE / STATE / SIGNAL / EXECUTION / RETRY
/topic/trading/lifecycle   - CREATED / CLOSED
/topic/system              - MARKET_MODE / TOKEN_STATUS / HOLIDAY / BALANCE_INVALIDATED
```

`BALANCE_INVALIDATED`는 lifecycle `CREATED`/`CLOSED`와 함께 자동 발행.

---

## 사전 정비

- [ ] 도메인 이벤트 발행 hook 위치 식별 — 현 코드의 분기점들이 spec §11.5 토픽과 1:1 매핑되는지 점검 (필요 시 분기 보강)

## 도메인 이벤트 정의 + 발행

- [ ] 이벤트 클래스 정의 (`at.backend.event` 또는 feature별 `event` 패키지):
  - `TradingCycleCreated`, `TradingCycleClosed`
  - `PriceUpdated`, `CycleStateChanged`, `SignalArmed`, `SignalFired`, `OrderExecuted`, `RetryAccumulated`
  - `MarketModeChanged`, `TokenStatusChanged`, `HolidayChanged`, `BalanceInvalidated`
- [ ] Spring `ApplicationEventPublisher`로 발행 — 위치:
  - `TradingService.create` afterCommit → `TradingCycleCreated` + `BalanceInvalidated`
  - `TradingCycle.close` 또는 runner의 `close` 호출 후 → `TradingCycleClosed` + `BalanceInvalidated`
  - `OrderExecutor.executeBuyTry/executeSell` 발사 시 → `OrderSubmitted` (선택)
  - `ExecutionNoticeHandler` 체결 적용 → `OrderExecuted`
  - Runner `updateArming` → `SignalArmed`
  - Runner `executeSignalSell` 진입 → `SignalFired`
  - Runner `processTick` 또는 별도 throttle → `PriceUpdated`
  - `MarketDataStream._mode` 변경 → `MarketModeChanged`
  - `TradingSchedulerService` 휴장 토글 → `HolidayChanged`
  - KIS 토큰 갱신 결과 → `TokenStatusChanged`

## STOMP 인프라

- [ ] `WebSocketConfig` — STOMP broker `/topic`, endpoint `/ws` 등록
- [ ] CORS / 인증 정책 (현재 단일 사용자라면 인증 stub)
- [ ] 페이로드 DTO (`broadcast/payload/`):
  - `TradingTopicPayload` 변종 (PRICE / STATE / SIGNAL / EXECUTION / RETRY)
  - `LifecyclePayload` (CREATED / CLOSED)
  - `SystemPayload` (MARKET_MODE / TOKEN_STATUS / HOLIDAY / BALANCE_INVALIDATED)

## StatusBroadcastHandler

- [ ] `@Component class StatusBroadcastHandler(simpMessagingTemplate, ...)` — `@EventListener`로 도메인 이벤트 수신 → 페이로드 변환 → STOMP 토픽 발행
- [ ] CREATED/CLOSED 시 BalanceInvalidated 자동 동반 발행 (이벤트 합성 또는 publisher가 둘 다 publish)

## 통합 테스트 (`StatusBroadcastTest`, IntegrationTestBase + STOMP 클라이언트)

- [ ] `/topic/trading/{id}` PRICE 1건 — tick 발생 시 페이로드 수신
- [ ] `/topic/trading/{id}` STATE 1건 — HOLDING/LIQUIDATING/CLOSED 전이
- [ ] `/topic/trading/{id}` SIGNAL 1건 — Breakeven 무장 / TpStage 발동
- [ ] `/topic/trading/{id}` EXECUTION 1건 — 매수 또는 매도 체결
- [ ] `/topic/trading/{id}` RETRY 1건 — 매도 발송 실패 누적
- [ ] `/topic/trading/lifecycle` CREATED + CLOSED 1건씩
- [ ] `/topic/system` MARKET_MODE / HOLIDAY / BALANCE_INVALIDATED 각 1건씩
- [ ] CREATED 발행 시 BALANCE_INVALIDATED 동반 검증

## Verification

- [ ] `./gradlew test` 전체 통과
- [ ] `at.backend.broadcast` (또는 해당 위치) 라인 커버리지 70%+
- [ ] STOMP 페이로드가 spec §11.5 스키마와 1:1 일치 (필드명/타입)

---

## Definition of Done

- 위 통합 테스트 통과
- 프론트(Phase 5-B)가 토픽을 구독해 PRICE/STATE/SIGNAL/EXECUTION/RETRY/lifecycle/system 8종 페이로드를 그대로 사용 가능
- `TokenStatus`/`Holiday` 발행은 해당 변경 시점이 시스템에 존재하면 발행, 없으면 후속 이슈로 분리

## 후속 / Phase 5-B (tasks-007)

- 프론트 STOMP 클라이언트 + 자동 재연결
- TanStack Query 도입 → mock 제거
- React Hook Form + Zod 폼 검증
- 모니터링/명령 화면의 토픽 구독 흐름 (spec §11.5)
- 종료 토스트, 시스템 상태 배지, BALANCE_INVALIDATED 처리
