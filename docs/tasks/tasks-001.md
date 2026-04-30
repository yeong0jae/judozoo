# tasks-001: Phase 1 — 도메인 코어 + 단위 테스트

본 문서는 [plan.md Phase 1](../plan.md#phase-1-도메인-코어--단위-테스트)의 작업을 체크 가능한 단위로 분해한 것이다.

## 진입 조건

- Phase 0 DoD 만족 (`./gradlew clean build` 통과, JSON 로깅, CoroutineScope Bean)
- 외부 의존 없음 — KIS API / DB 호출 없이 순수 도메인 로직만

## 종료 조건 (Phase 1 DoD)

- plan.md §Phase 1 검증 시나리오 8개 단위 테스트 통과
- 도메인 레이어(`trading.domain`) 커버리지 90%+
- 소스 내 KIS 호출 / DB 접근 / `System.currentTimeMillis()` 직접 사용 없음

---

## 1. 테스트 의존성 추가 (`backend/build.gradle.kts`)

- [x] Kotest 추가 — `io.kotest:kotest-runner-junit5:5.x` + `io.kotest:kotest-assertions-core:5.x`
- [x] MockK 추가 — `io.mockk:mockk:1.x` (Phase 1은 순수 도메인이라 mock 사용 없음, Phase 2부터 필요)
- [x] JaCoCo 플러그인 추가 — `apply plugin: 'jacoco'` + `jacocoTestReport` task 설정 (80% 미만 빌드 실패 X, 리포트만)
- [x] `./gradlew clean build` 통과 확인

---

## 2. 공통 도메인 모델 (`trading.domain`)

- [x] `PriceTick` data class 작성 — `stockCode: String`, `price: Int`, `timestamp: Instant`
- [x] `Bar` data class 작성 — 3분봉 표현 (`stockCode: String`, `openPrice: Int`, `closePrice: Int`, `startTime: Instant`, `endTime: Instant`)
- [x] `CloseReason` enum 작성 — `TAKE_PROFIT`, `STOP_LOSS`, `BREAKEVEN`, `TREND_BREAK`, `MARKET_CLOSE`, `CANCELLED`, `NO_FILL`, `UNCLOSED`

---

## 3. CycleState sealed class (`trading.domain.cycle`)

- [x] `CycleState` sealed class 작성 — `Initiated` / `Buying(nextAttempt: Int)` / `Monitoring` / `Liquidating(reason: CloseReason)` / `Closed(reason: CloseReason)`
- [x] 전이 유효성 함수 작성 — `CycleState.canTransitionTo(next: CycleState): Boolean` (spec §5.2 전이 규칙)
- [x] 단위 테스트 (`CycleStateTest.kt`, Kotest FunSpec) — 허용 전이(`Initiated→Buying`, `Buying→Monitoring` 등) + 불허 전이(`Initiated→Monitoring`, `Closed→Buying` 등)

---

## 4. Execution 모델 + TradingRules 계산 함수 (`trading.domain.rule`)

- [x] `Execution` data class 작성 — `executedPrice: Int`, `executedQty: Int`, `fee: Int`
- [x] `TradingRules.calcBuyPrice(executions: List<Execution>, sellCostRate: Double): Int` 구현 — `(Σ(price × qty) + Σfee) / Σqty × (1 + sellCostRate)`, 절상(올림) 처리 (spec §7.6)
- [x] `TradingRules.calcSplitSellQty(holdingQty: Int, splitSellRatio: Double): Pair<Int, Int>` 구현 — 1회 분할 수량(절사) + 잔여량 반환
- [x] 단위 테스트 (`TradingRulesTest.kt`, FunSpec) — `calcBuyPrice` 수수료 + sellCostRate 반영 정확도, `calcSplitSellQty` 절사 + 잔여 처리(홀수 수량 케이스)

---

## 5. TradingRules 시그널 트리거 판정 함수 (`trading.domain.rule`)

- [x] `isStopLossTriggered(currentPrice: Int, buyPrice: Int, stopLossPct: Double): Boolean`
- [x] `isMidwayTakeProfitTriggered(currentPrice: Int, buyPrice: Int, midwayProfitPct: Double): Boolean`
- [x] `isTpStageTriggered(currentPrice: Int, buyPrice: Int, stagePct: Int, tpStagesFired: Int): Boolean` — 미발동 비트 확인 포함
- [x] `isBreakevenTriggered(currentPrice: Int, buyPrice: Int, armed: Boolean): Boolean`
- [x] `isTrendBreakTriggered(currentBar: Bar, prevBar: Bar, armed: Boolean): Boolean` — `현재 종가 < 1전봉 시가`
- [x] 단위 테스트 (`TradingRulesTest.kt`에 추가) — 각 트리거 경계값 케이스 (경계 이상 = 발동, 경계 미만 = 미발동)

---

## 6. Signal sealed class + SignalGuard (`trading.domain.signal`)

- [x] `Signal` sealed class 작성 — `StopLoss(priority=1)`, `MidwayTakeProfit(priority=2)`, `TpStage(pct:Int, priority=2)`, `Breakeven(priority=2)`, `TrendBreak(priority=2)`, `LimitUp(priority=2)`, `MarketClose(priority=0)`, `Cancel(priority=0)` (낮을수록 우선)
- [x] `SignalGuard` object 작성 — `isAlive(signal: Signal, currentPrice: Int, buyPrice: Int, currentBar: Bar?, clock: Instant): Boolean` (spec §7.3)
- [x] 단위 테스트 (`SignalGuardTest.kt`, FunSpec) — TrendBreak의 봉 진행 중(isAlive=true) vs. 봉 종료(isAlive=false) 케이스, Breakeven 현재가 초과(isAlive=false) 케이스

---

## 7. CycleSnapshot + SignalDetector (`trading.domain.signal`)

- [x] `CycleSnapshot` data class 작성 — SignalDetector가 평가에 필요한 상태 스냅샷 (`state: CycleState`, `holdingQty: Int`, `buyPrice: Int`, `tpStagesFired: Int`, `breakevenArmed: Boolean`, `trendBreakArmed: Boolean`, `buyAttempt: Int`)
- [x] `SignalDetector` object 작성 — `detect(tick: PriceTick, snapshot: CycleSnapshot, currentBar: Bar?, prevBar: Bar?): List<Signal>` (우선순위 오름차순 정렬, 보유=0이면 가격 기반 시그널 평가 보류)
- [x] 단위 테스트 (`SignalDetectorTest.kt`, FunSpec) — 우선순위 처리(StopLoss + TpStage 동시 → StopLoss만 반환), 보유=0 시 가격 기반 시그널 평가 보류, `tpStagesFired` 비트 중복 발동 방지

---

## 8. 시나리오 단위 테스트 1~4 (`trading.domain.scenario`)

- [ ] **시나리오 1**: 정상 사이클 — 3회 매수 체결 → TpStage(2/3/5%) 단계별 발동 → trendBreakArmed 무장 → TrendBreak 잔여 매도 → `Closed(TAKE_PROFIT)` 전이 확인
- [ ] **시나리오 2**: 중도 익절 — 매수 2회차 중 +3.5% 도달 → MidwayTakeProfit 발동 → `Buying→Monitoring` 전이, 잔여 보유 유지
- [ ] **시나리오 3**: 갭상승 복수 단계 — TpStage(2/3/5%) 동시 발동 → 3개 시그널 모두 반환, tp_stages_fired 비트 전부 세팅
- [ ] **시나리오 4**: 손절 우선순위 — TpStage 무장 상태 + 현재가 -2% 동시 → `detect()` 결과 StopLoss 단독 반환(TpStage 억제)

---

## 9. 시나리오 단위 테스트 5~8 (`trading.domain.scenario`)

- [ ] **시나리오 5**: 본전 매도 무장 후 발동 — +2% 도달(breakevenArmed=true) → 매수가 도달 → Breakeven 발동, SignalGuard.isAlive=true 유지
- [ ] **시나리오 6**: 추세 꺾임 봉 종료 재발동 — trendBreakArmed=true + 현재봉에서 TrendBreak 발동 → 봉 종료 후 SignalGuard.isAlive=false → 다음 봉 재충족 시 TrendBreak 재발동
- [ ] **시나리오 7**: NO_FILL — 3회 매수 완료 후 holdingQty=0 → `Buying→Closed(NO_FILL)` 직행, Liquidating 거치지 않음
- [ ] **시나리오 8**: 시그널 평가 보류 — holdingQty=0 상태에서 가격이 손절선 이하 → `detect()` 결과 빈 리스트 (StopLoss 평가 안 됨)

---

## 10. 검증 (Phase 1 DoD 체크)

- [ ] `./gradlew test` 전체 통과
- [ ] `./gradlew jacocoTestReport` 실행 → `backend/build/reports/jacoco/test/html/index.html` 열어 `trading.domain` 라인 커버리지 90%+ 확인
- [ ] 소스 파일(`trading/domain/**/*.kt`) 전체에 `System.currentTimeMillis()` / KIS 패키지 import / JPA 어노테이션 없음 확인

---

## Phase 1 종료 시점 산출물

```
backend/src/main/kotlin/at/backend/trading/domain/
├── PriceTick.kt
├── Bar.kt
├── CloseReason.kt
├── cycle/
│   └── CycleState.kt
├── signal/
│   ├── Signal.kt
│   ├── SignalGuard.kt
│   ├── SignalDetector.kt
│   └── CycleSnapshot.kt
└── rule/
    ├── TradingRules.kt
    └── Execution.kt

backend/src/test/kotlin/at/backend/trading/domain/
├── cycle/
│   └── CycleStateTest.kt
├── signal/
│   ├── SignalGuardTest.kt
│   └── SignalDetectorTest.kt
├── rule/
│   └── TradingRulesTest.kt
└── scenario/
    └── TradingScenarioTest.kt
```

---

## 다음 Phase

Phase 1/2는 병렬 가능.
- Phase 2 → tasks-002.md (KIS REST 어댑터)
