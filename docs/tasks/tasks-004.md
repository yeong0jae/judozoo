# tasks-004 — Phase 4: 매매 사이클 엔진 (백엔드 자동 매매)

Goal: `TradingService.create()`가 접수된 사이클을 백그라운드에서 자동으로 매수→시그널 감지→매도→종료까지 운영. 가장 위험한 단계 — 동시성·멱등성·시세 fallback 전부 포함.

> Phase 1 도메인 룰(`TradingCycle.detectSignals`, `canTransitionTo` 등)과 Phase 2 KIS REST(`KisRestClient`), Phase 3 명령 접수(`TradingService`, `TradingValidator`)는 완료. 본 Phase는 코루틴 오케스트레이션 + WebSocket + OrderExecutor를 새로 추가.

---

## 4b. MarketDataStream (시세 스트림)

- [x] `platform.kis.client.KisWebSocketClient`: Spring `StandardWebSocketClient`; `subscribePrice(stockCode)` / `unsubscribePrice(stockCode)` / `subscribeExecutionNotice(htsId)`; `H0STCNT0`(체결가) 통보 → `SharedFlow<PriceTick>`; `H0STCNI0`(체결 통보) → `SharedFlow<ExecutionNotice>`; onClose 시 백오프 재연결 (1s → 2s → 5s → 5s …); `KisApprovalKeyProvider`로 WS approval_key 발급
- [ ] `market.application.MarketDataStream` *(신규 feature 패키지)*: 활성 종목 set 관리; WS 정상이면 통보 그대로 전달, 끊김 동안 1초 간격 REST 폴링(`KisRestClient.getCurrentPrice`)으로 동일 `Flow<PriceTick>` 발행; 재연결 성공 시 폴링 중단; 모드 변경(`WS`/`POLLING`) 이벤트 발행
- [ ] `market.infrastructure.BarCache`: 3분봉 캐시; 30초마다 `KisRestClient.getBars(code)` 폴링; 새 봉 닫힘 감지 시 `Flow<BarEvent>` 발행
- [ ] `MarketDataStreamTest` (IntegrationTestBase + WireMock WebSocket): WS 정상 시 `PriceTick` 발행 / WS 끊김 → 1초 폴링 fallback / 재연결 시 폴링 중단 / 다중 종목 구독·해제 / 모드 변경 이벤트
- [ ] `BarCacheTest` (IntegrationTestBase + WireMock REST): 30초 폴링 시뮬레이션 → 새 봉 닫힘 감지 시에만 발행 / 미변경 시 무발행

## 4a. OrderExecutor (주문 발송 + 멱등성)

- [ ] `KisRestClient` 주문 메서드 추가: `submitOrder(order, clientOrderId)` (`POST /uapi/domestic-stock/v1/trading/order-cash`), `cancelRemainder(order)` (`POST /uapi/domestic-stock/v1/trading/order-rvsecncl`); 헤더에 `clientOrderId = order.id (UUID)` 포함
- [ ] WireMock 시나리오 추가 (`order-cash-200.json`, `order-rvsecncl-200.json`) + 정상/4xx/5xx/타임아웃 단위 테스트 (`KisRestClientTest`에 추가)
- [ ] `trading.application.OrderExecutor.executeBuyTry(cycle, attempt)`: `marketData.currentPrice` → `qty = perBuyAmount/price` → `Order(BUY, MARKET)` 저장 → `kis.submitOrder` → `waitSettlement(timeout=5s)` → Filled / Partial→cancelRemainder; 발송 실패 시 `order.markFailed` + 회차 스킵 (BUYING 유지)
- [ ] `trading.application.OrderExecutor.executeSell(cycle, signal, intentQty)`: 재시도 루프 — `signalGuard.isAlive` 가드 → `effectiveQty = intentQty - inFlightUnfilled` (B-3 충돌 방지) → `Order(SELL, MARKET)` → `submitOrder` + `waitSettlement` → Filled 시 종료; 타임아웃 시 `reconcileFromKis` (일별 체결 조회 매칭); `delay(5s)` 후 재시도; `orders.retry_count` / `last_error` 매 시도 갱신
- [ ] `OrderJpaRepository` 추가 메서드: `inFlightSellUnfilled(cycleId): Int`, `findByClientOrderId(uuid)`
- [ ] `OrderExecutorTest` (IntegrationTestBase, `KisRestClient` MockK `@TestConfiguration + @Primary`): 매수 정상 체결 / 매수 부분 체결 → 잔량 취소 / 매수 발송 실패 → 회차 스킵 / 매도 정상 / 매도 타임아웃 → 일별 체결 reconcile / B-3 충돌 (TpStage in-flight 중 StopLoss 발동 → `effectiveQty` 차감) / 재시도 카운트 누적

## 4c. TradingCycle 코루틴 + 오케스트레이션

- [ ] `library.coroutine.ApplicationCoroutineScope` 빈: `SupervisorJob + Dispatchers.Default`; 애플리케이션 종료 시 `cancelAndJoin`
- [ ] `trading.application.TradingCycleRunner`: 명령별 코루틴; 내부 Mutex로 상태 전이 / 시그널 처리 직렬화; 단계 — INITIATED→BUYING(1차 발사) → `delay(buyIntervalMin)` × 2 → HOLDING; HOLDING 중 `Flow<PriceTick>` + `Flow<BarEvent>` collect → `cycle.detectSignals` → 우선순위 정렬 → `OrderExecutor.executeSell` → 결과에 따라 상태 전이/종료
- [ ] `trading.application.CycleOrchestrator`: `MarketDataStream` 구독 lifecycle 관리; `PriceTick`/`BarEvent`을 `stockCode`로 활성 사이클에 라우팅; `TradingService.create()` 후 `start(cycle)`, `cancel()` 시 LIQUIDATING 흐름으로 라우팅
- [ ] `TradingService.create()` 통합: 저장 직후 `CycleOrchestrator.start(cycle)` 호출 (Phase 3 동작 보존하면서 백그라운드 시작 추가)
- [ ] `TradingService.cancel()` 통합: in-flight 매수 주문 취소 + 보유 > 0 → MarketClose 시그널 라우팅 → 청산 / 보유 = 0 → CLOSED(CANCELLED) 직행
- [ ] `trading.infrastructure.scheduler.TradingSchedulerService`:
  - `@Scheduled(cron = "0 20 15 * * MON-FRI", zone = "Asia/Seoul")`: 모든 활성 사이클에 MarketClose 시그널 발행
  - `@Scheduled(cron = "0 0 8 * * MON-FRI", zone = "Asia/Seoul")`: 영업일 검증 + 명령 접수 게이트 토글
- [ ] `BackendApplication` 시작 hook: 부팅 시 `status IN (INITIATED, BUYING, HOLDING, LIQUIDATING)` 사이클 조회 → 각 사이클 `closedAt = now`, `status = CLOSED`, `closeReason = UNCLOSED` 일괄 저장 (시스템 다운 후 재시작 시 자동 마감)

## End-to-end 통합 테스트 (`TradingCycleE2ETest`, IntegrationTestBase)

- [ ] 정상 사이클: 3회 매수 → 봉 종료에서 +2%/+3%/+5% 단계 발동 → 추세 꺾임 잔여 매도 → CLOSED(TAKE_PROFIT)
- [ ] 손절: HOLDING 중 -2% → CLOSED(STOP_LOSS)
- [ ] 본전 매도: +2% 도달 무장 → 매수가 도달 → 전량 매도 → CLOSED(BREAKEVEN); 무장만 된 봉 종료 후 다음 봉 재충족 시 재발동 검증
- [ ] 취소: BUYING 1차 in-flight 중 cancel → in-flight 매수 취소 + 보유분 청산 → CLOSED(CANCELLED)
- [ ] 부분 체결 / NO_FILL: 3회 모두 미체결 → 보유 0 → CLOSED(NO_FILL) 직행
- [ ] WS 끊김 + REST 폴링 fallback: WebSocket 강제 종료 → 폴링으로 시그널 평가 지속 → 재연결 시 WS 복귀
- [ ] 주문 타임아웃 reconcile: 매도 5초 무응답 → 일별 체결 조회 매칭 → 재발사 안 함
- [ ] 다중 종목 동시 운용 (3개): 동일 흐름이 격리되어 동시 진행, 서로의 Mutex/잔고에 영향 없음
- [ ] 15:20 강제 청산: TpStage 분할 익절 후 잔여 보유분 → MarketClose 일제 발행 → CLOSED(MARKET_CLOSE)
- [ ] 시스템 다운 후 재시작 자동 마감: 활성 사이클 있는 상태에서 재기동 → 모두 CLOSED(UNCLOSED) 검증

## Verification

- [ ] `./gradlew test` 전체 통과
- [ ] 코루틴 누수 / Mutex deadlock 없음 (E2E 반복 실행 3회 안정)
- [ ] `at.backend.trading` + `at.backend.market` 라인 커버리지 70%+

---

## Definition of Done

- 위 9개 E2E 시나리오 통과
- 시스템 다운 후 재시작 시 활성 사이클 → UNCLOSED 자동 마감 검증
- Phase 5 진입 가능 상태 (도메인 이벤트 발생점이 STOMP broadcast hook 부착 가능 위치에 정렬됨)
