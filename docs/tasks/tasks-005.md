# tasks-005 — Phase 4.5: 매매 사이클 엔진 E2E 검증

Goal: Phase 4에서 구축한 사이클 엔진(`TradingCycleRunner`, `CycleOrchestrator`, `OrderExecutor`, 스케줄러, 시작 hook)을 9개 E2E 시나리오 + 안정성/커버리지 게이트로 검증한다. Phase 5(브로드캐스트) 진입 직전의 실전 합격 라인.

> 기반 구현은 `tasks-004.md`에서 완료. 본 단계는 동작 검증과 발견된 결함 보강.

---

## 사전 정비

- [x] BUYING 단계 cancel 실시간 반영: `TradingService.cancel`이 `Signal.Cancel`을 큐잉해도 `runBuySequence` 루프가 끝까지 회차 진행하는 문제 해결
  - 옵션 B 채택 — orchestrator가 runner의 매수 코루틴(`buyJob`)을 cancelAndJoin + in-memory cycle을 LIQUIDATING으로 전이 + `Signal.Cancel` 채널 push
  - `runBuySequence` finally를 `NonCancellable`로 감싸 `finalizeBuySequence` 보장, LIQUIDATING/filled==0 → CANCELLED close 분기 추가
- [x] (필요 시) 회차 진행 중 cycle 상태 동기화 — DB의 `LIQUIDATING`이 runner의 in-memory `BUYING` 상태로 덮어써지는 race를 차단
  - 옵션 B 적용으로 자동 해결 — 매수 코루틴이 cancel되면 `incrementBuyAttempt`/save가 더 이상 실행되지 않음

## 사이클 시나리오 통합 테스트 (`TradingCycleScenarioTest`, IntegrationTestBase)

> 진입은 `TradingService.create/cancel` (application layer). 외부(KIS REST/WS)만 mock.
> 진정한 E2E(controller+HTTP)는 별도 `*ControllerTest`에서 다룬다.

- [x] 정상 사이클: 3회 매수 → +2%/+3%/+5% 단계 분할 익절 → 추세 꺾임 잔여 매도 → CLOSED(TREND_BREAK)
  - 명세 정정 — 잔여를 trend break으로 청산하면 closeReason은 `TREND_BREAK`. 명세의 TAKE_PROFIT은 splitSellRatio가 1.0일 때만 발생
- [x] 손절: HOLDING 중 -2% → CLOSED(STOP_LOSS)
  - 작성 중 운영 버그 발견 — `isStopLossTriggered`가 stopLossPct를 decimal로 곱했음(다른 비율은 percent /100). 같은 PR에서 fix
- [x] 본전 매도: +2% 분할 익절(TpStage(2)) 후 매수가 회귀 시 잔여 매도 → CLOSED(BREAKEVEN)
- [x] 취소: BUYING 1차 in-flight 중 cancel → in-flight 매수 취소 + 보유분 청산 → CLOSED(CANCELLED)
- [x] 부분 체결 / NO_FILL: 3회 모두 미체결 → 보유 0 → CLOSED(NO_FILL) 직행
- [x] WS 끊김 + REST 폴링 fallback: WebSocket 강제 종료 → 폴링으로 시그널 평가 지속 → 재연결 시 WS 복귀
- [x] 주문 타임아웃 reconcile: 매도 5초 무응답 → 일별 체결 조회 매칭 → 재발사 안 함
- [x] 다중 종목 동시 운용 (3개): 동일 흐름이 격리되어 동시 진행, 서로의 Mutex/잔고에 영향 없음
- [x] 15:20 강제 청산: TpStage 분할 익절 후 잔여 보유분 → MarketClose 일제 발행 → CLOSED(MARKET_CLOSE)
- [x] 시스템 다운 후 재시작 자동 마감: 활성 사이클 있는 상태에서 재기동 → 모두 CLOSED(UNCLOSED) 검증

## Verification

- [x] `./gradlew test` 전체 통과
- [x] 코루틴 누수 / Mutex deadlock 없음
- [x] `at.backend.trading` + `at.backend.market` 라인 커버리지 70%+ (실측 ~94%)

---

## Definition of Done

- 위 9개 E2E 시나리오 통과
- 시스템 다운 후 재시작 시 활성 사이클 → UNCLOSED 자동 마감 검증
- Phase 5 진입 가능 상태 (도메인 이벤트 발생점이 STOMP broadcast hook 부착 가능 위치에 정렬됨)
