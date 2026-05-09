# SPEC: 주도주 자동 트레이딩 시스템 기술 명세

[PRD](./prd.md)의 "무엇을"에 대해 "어떻게"를 정의한다.

---

## 1. 아키텍처

### 1.1 구성

```
Frontend (TS + React + Vite + Tailwind)
  - 매매 명령 / 모니터링 / 실적
  - STOMP로 실시간 상태 수신

Backend (Kotlin + Spring Boot 4, package-by-feature)
  - trading : 매매 사이클 접수/취소/조회 + 엔진(상태머신/시그널/주문)
  - market  : 시세 스트림 (WS + REST polling fallback)
  - report  : 일별 실적 집계
  - stock   : 종목 검색
  - account : 잔고
  - system  : 시스템 상태
  - platform/kis : KIS 공통 인프라 (인증/REST/WS/RateLimit)
  - library : 공유 유틸 (예외/시간/로깅/WS)

  도메인 내부 계층: presentation → application → domain ← infrastructure
```

### 1.2 설계 원칙

- **Package by feature** — 도메인 간 호출은 `application` 계층끼리만
- **Spring Web MVC + Coroutines** — presentation 동기, 백그라운드 매매는 코루틴
- **종목별 격리** — 사이클당 코루틴 + Mutex
- **이벤트 기반** — `Flow<PriceTick>` → SignalDetector → OrderExecutor
- **상태 머신** — Kotlin sealed class로 컴파일 타임 케이스 강제
- **멱등성** — KIS 발급 ODNO + `inquire-daily-ccld` reconcile (개인 계좌라 client-supplied 멱등키 미사용)

---

## 2. 기술 스택

**Backend**: Kotlin 2.2 / JVM 21 / Spring Boot 4 + Web MVC / kotlinx.coroutines / Spring Data JPA + Hibernate / MySQL 8 / Spring `RestClient` / OkHttp WebSocket / Spring WebSocket+STOMP / Logback (JSON encoder) / JUnit 5 + Kotest + MockK + Testcontainers + WireMock.

**Frontend**: TypeScript + React 18 + Vite / TanStack Query + Zustand / React Router / Tailwind CSS / React Hook Form + Zod / @stomp/stompjs.

---

## 3. 데이터 모델 (MySQL)

### 3.1 `trading_cycles`
PK `id`, `stock_code/name`, `per_buy_amount`, `buy_interval_min`, `split_sell_ratio`, `midway_profit_pct`, `breakeven_threshold_pct`, `stop_loss_pct`, `status` (INITIATED/BUYING/HOLDING/LIQUIDATING/CLOSED), `close_reason`, `buy_attempt`, `breakeven_armed`, `trend_break_armed`, `tp_stages_fired` (비트 b0=2%, b1=3%, b2=5%), `created_at/updated_at/closed_at`.

> `TradingCycle` JPA 엔티티가 이 테이블을 직접 매핑하며 도메인 로직(상태 전이 / 시그널 감지 / 매수가 산정)을 함께 보유. `holdingQty`/`buyPrice`는 `orders`/`executions`에서 집계해 파라미터로 전달.

### 3.2 `orders`
PK `id`, FK `cycle_id`, `side` (BUY/SELL), `trigger` (BUY_TRY_1/2/3, TP_2PCT/3PCT/5PCT/REMAINDER, MIDWAY_TP, BREAKEVEN, STOP_LOSS, MARKET_CLOSE, CANCEL), `order_qty`, `filled_qty`, `order_type` (MARKET 고정), `kis_order_no`, `status` (PENDING/SUBMITTED/PARTIAL/FILLED/CANCELLED/FAILED), `retry_count`, `last_error`.

### 3.3 `executions`
PK `id`, FK `order_id`, `executed_qty`, `executed_price`, `fee`, `tax`.

### 3.4 인덱스
- `trading_cycles(status, stock_code)` — 활성 조회
- `trading_cycles(created_at)` — 일별 리포트
- `orders(cycle_id)`, `executions(order_id)`

### 3.5 스키마 관리
Hibernate `ddl-auto=update`. destructive 변경은 v1 운영 진입 시점에 별도 정책.

---

## 4. 매매 사이클 상태 머신

### 4.1 상태 / 전이

```
Initiated ─(1차 매수 시도 시작)─▶ Buying
Buying ─(3회완료+보유>0 OR 중도익절+보유>0)─▶ Holding
Buying ─(손절/취소+보유>0)─▶ Liquidating
Buying ─(3회완료+보유=0 OR 취소+보유=0)─▶ Closed (NO_FILL / CANCELLED)
Holding ─(TpStage 부분매도, 잔여>0)─▶ Holding (자기루프)
Holding ─(BE/TB/LU/MC 잔여 전량매도)─▶ Liquidating
Liquidating ─(매도완료 보유=0)─▶ Closed (close_reason은 발동 시그널)
```

### 4.2 동시성
- 사이클별 `TradingCycleRunner`가 단일 outer 코루틴 안에서 tick / signal / bar 수집 + 매수 시퀀스 + holding 폴링을 구동. 명시적 Mutex는 사용하지 않음.
- 다른 명령은 독립 코루틴 — 격리.
- 단일 `ApplicationCoroutineScope` (SupervisorJob + Dispatchers.IO).
- 한 사이클 안의 가변 상태(`prevBar`, `currentBar`, `pendingCloseReason`, `buyJob`)는 같은 outer 코루틴 트리 안의 자식 코루틴들이 접근 — 협력적 스케줄링과 사이클당 트래픽 수준이 낮은 점에 의존해 lock-free로 단순화. 강한 동시성 보장이 필요한 변경은 별도 검토.

---

## 5. 시그널 (`SignalDetector`)

### 5.1 표현 + 우선순위

```kotlin
sealed class Signal {
  data object MarketClose : Signal()    // priority 0 (최우선)
  data object Cancel      : Signal()    // priority 0
  data object StopLoss    : Signal()    // priority 1
  // priority 2: MidwayTakeProfit, TpStage(pct), Breakeven, TrendBreak, LimitUp
}
```

### 5.2 트리거 조건

| 시그널 | 조건 | 발동 가능 상태 |
|---|---|---|
| StopLoss | 현재가 ≤ 매수가 × (1 + stopLossPct) | Buying / Holding (보유>0) |
| MidwayTakeProfit | 현재가 ≥ 매수가 × (1 + midwayProfitPct) | Buying (회차<3, 보유>0) |
| TpStage(2/3/5) | 현재가 ≥ 매수가 × (1+N/100) AND 비트 미발동 | Holding |
| Breakeven | breakeven_armed AND 현재가 ≤ 매수가 | Holding |
| TrendBreak | trend_break_armed AND 현재 3분봉 종가 < 1전봉 시가 | Holding 잔여. 봉 종료 시 isAlive=false → 다음 봉 재충족 시 재발동 |
| LimitUp | 현재가 = 상한가 | Holding 잔여 |
| MarketClose | KST ≥ 15:20 | 모든 활성 (보유=0이면 즉시 Closed) |
| Cancel | 사용자 요청 | INITIATED/BUYING/HOLDING (보유=0이면 Closed=CANCELLED) |

> **보유 = 0 동안 가격 기반 시그널 평가 보류** — 매수가 미정의. 첫 부분 체결 시점부터 평가 시작.

### 5.3 우선순위
동일 틱에 복수 시그널 발동 시 `MarketClose, Cancel > StopLoss > 그 외`. StopLoss 발동 시 다른 시그널 자동 비활성화.

### 5.4 영속화
`breakeven_armed`, `trend_break_armed`, `tp_stages_fired`는 무장으로 전이 시 즉시 UPDATE. 가격 진동으로 인한 반복 발동 방지.

### 5.5 추세 꺾임 데이터 부족
KIS 3분봉 응답에서 직전봉 없으면 (`bars.size<2`) TrendBreak 보류. 09:00–09:03 사이 +5% 도달 시 잔여는 LimitUp/MarketClose까지 보유.

---

## 6. 주문 실행 (`OrderExecutor`)

### 6.1 매수 회차
시장가 발송 → 5초 settlement 대기 → Filled / Partial(잔량 취소) / Pending. **발송 실패는 회차 스킵**, BUYING 유지하며 다음 회차 정상 시도. `Initiated → Buying` 전이는 1차 시도 *시작* 시점 (발송 성공/실패 무관).

### 6.2 매도 (재시도 루프)
isAlive 가드 → **B-3 충돌 방지** (effectiveQty = intentQty - 진행중 매도 미체결분) → 발송 → 5초 대기 → Filled 시 종료, 타임아웃 시 reconcile, 그 외 5초 delay 후 재시도. `retry_count` / `last_error` / `RETRY` 이벤트로 가시성 확보.

### 6.3 멱등성
**KIS 멱등키 미사용** (개인 계좌 제약). `Order.kisOrderNo`(KIS 발급 ODNO) + `inquire-daily-ccld` 1회 reconcile.
- ODNO 회수 → `H0STCNI0` WS 통보로 체결 누적
- 타임아웃/통보 누락 시 일별 체결 조회 → `kisOrderNo` 매칭 또는 시간±30s + 종목 + side + 수량 매칭
- 매칭 0건 → 새 주문 발송, 2건+ → `NEEDS_MANUAL_REVIEW` 표기

### 6.4 매수가 (Average Cost) 산정

PRD §매수가 산정 기준 — 매수 비용 + 예상 매도 비용 반영 손익 0 기준가:
```
매수 원가평균 = (Σ(executed_price × executed_qty) + Σ buy_fee) / Σ executed_qty
매수가         = 매수 원가평균 × (1 + sellCostRate)    # 0.0025 = 매도 거래세 + 수수료 + 버퍼
```
모든 시그널 트리거가 이 단일 매수가를 기준으로 평가. 새 체결마다 재계산 후 cycle in-memory + DB UPDATE.

### 6.5 NO_FILL
3회 매수 시도 후 누적 체결 = 0이면 Liquidating 거치지 않고 즉시 Closed(NO_FILL). `STATE` 이벤트 발송.

---

## 7. KIS API 통합

### 7.1 인증
`KisAccessTokenProvider` lazy 1회 발급 (현재). 자동 갱신 + 실패 노출은 후속 작업. 시크릿: `application-local.yaml` 또는 환경변수.

### 7.2 사용 엔드포인트

| 용도 | KIS 엔드포인트 | TR ID (실전) |
|---|---|---|
| 토큰 발급 | `POST /oauth2/tokenP` | - |
| 영업일 조회 | `/quotations/chk-holiday` | CTCA0903R |
| 종목 기본조회 | `/quotations/search-stock-info` | CTPF1002R |
| 현재가 | `/quotations/inquire-price` | FHKST01010100 |
| 분봉 | `/quotations/inquire-time-itemchartprice` | FHKST03010200 |
| 주문 | `/trading/order-cash` | (BUY/SELL 별도) |
| 정정/취소 | `/trading/order-rvsecncl` | - |
| 잔고 | `/trading/inquire-balance` | TTTC8434R |
| 일별 체결 | `/trading/inquire-daily-ccld` | - |
| 실시간 시세 (WS) | TR `H0STCNT0` | - |
| 실시간 체결 통보 (WS) | TR `H0STCNI0` | - |

### 7.3 Rate Limit
실거래 REST 초당 20건. `KisRateLimiter` (Resilience4j 토큰 버킷) acquire 후 발신.

---

## 8. 시세 스트림 (`MarketDataStream`)

명령 등록 시 `subscribe(stockCode)` → KIS WS 구독 → `Flow<PriceTick>` 발행. 끊김 시 백오프 재연결(1s→2s→5s) + 그 동안 1초 간격 REST 폴링 fallback (동일 Flow). 재연결 성공 시 자동 복귀. `/topic/market` `MARKET_MODE`로 프론트 알림. **3분봉**: TrendBreak용 30s REST 폴링 + 캐시.

---

## 9. 스케줄러 (`TradingSchedulerService`)

- **명령별 타이머**: `TradingCycle` 코루틴 안에서 `delay(buyIntervalMin.minutes)`로 회차 트리거
- **글로벌 cron**: `0 20 15 * * MON-FRI` MarketClose 일제 발행, `0 0 8 * * MON-FRI` 영업일 게이트 토글
- **컷오프**: `TradingService.create()`에서 KST 시각 vs `15:20 - buyIntervalMin × 2` 비교, 초과 시 `CUTOFF_PASSED`

---

## 10. REST API

### 10.1 트레이딩

```
POST /api/trading              { stockCode, perBuyAmount, [advanced 5필드] } → 201 { cycleId, status }
  errorCodes: STOCK_NOT_FOUND / PRICE_BELOW_ONE_SHARE / INSUFFICIENT_BALANCE /
              DUPLICATE_COMMAND / CUTOFF_PASSED / HOLIDAY / OUT_OF_TRADING_HOURS /
              COMMAND_GATE_CLOSED / INVALID_PARAMETER

DELETE /api/trading/{id}       → 202 { status, closeReason? } / 409 ALREADY_CLOSED
  - INITIATED/BUYING/HOLDING + 보유>0 → LIQUIDATING
  - INITIATED/BUYING/HOLDING + 보유=0 → CLOSED(CANCELLED) 직행
  - LIQUIDATING                       → 멱등 (LIQUIDATING 유지)

GET /api/trading?status=active|today    → TradingSummary[] | DailyTrading[]
GET /api/trading/{id}                   → TradingDetail (orders[], executions[], armed/tpStages 포함)
```

### 10.2 실적
```
GET /api/reports/daily?date=YYYY-MM-DD → DailyReport[]
  { cycleId, stockCode/Name, closeReason, createdAt, closedAt,
    avgBuyPrice, avgSellPrice, totalFee, totalTax, grossProfit, netProfit, profitRate }
```
UI는 closeReason = UNCLOSED / NO_FILL 강조.

### 10.3 종목 / 시세
```
GET /api/stocks/search?q=keyword   → [{ stockCode, stockName }]   # KIS PDNO 정확 매치 (6자리)
GET /api/stocks/{stockCode}/price  → { stockCode, currentPrice, asOf }
```

### 10.4 계좌 / 시스템
```
GET /api/account/balance → { cashBalance, reservedAmount, availableBalance }
GET /api/system/status   → { marketMode: "WS"|"POLLING", tokenStatus, isHoliday, tradingHoursOpen, cutoffPassed }
```

### 10.5 STOMP (`/ws`)

```
/topic/trading/{id}:
  PRICE     { currentPrice, profitRate, profitAmount, ts }
  STATE     { status, closeReason?, ts }
  SIGNAL    { signalType, event:"ARMED"|"FIRED", stage?, ts }
  EXECUTION { side, qty, price, totalFilledQty, holdingQty, averageBuyPrice, ts }
  RETRY     { signalType, retryCount, lastError, ts }

/topic/trading/lifecycle:
  CREATED { cycleId, stockCode, stockName, ts }
  CLOSED  { cycleId, closeReason, ts }

/topic/market:
  MARKET_MODE { mode:"WS"|"POLLING", ts }
  HOLIDAY     { isHoliday, ts }

/topic/account:
  BALANCE_INVALIDATED { ts }   # lifecycle CREATED/CLOSED와 동반 발행
```

**프론트 구독 흐름**:
- 모니터링 — `system/status` + `trading?status=active` 초기, lifecycle/per-id/market 구독
- 매매 명령 — `system/status` + `account/balance` 초기, account/market 구독, 종목 선택 시 stocks/{code}/price

---

## 11. 설정 (`application.yaml`)

```yaml
spring.datasource.url: jdbc:mysql://localhost:3306/autonomous_trading
spring.jpa.hibernate.ddl-auto: update
spring.jpa.open-in-view: false

kis:
  app-key/secret/account-no/account-product-code: ${env}
  base-url: https://openapi.koreainvestment.com:9443
  ws-url:   ws://ops.koreainvestment.com:21000
  rate-limit-per-second: 20

trading:
  market-close-time: "15:20"
  default-buy-interval-min: 3
  default-split-sell-ratio: 0.20
  default-midway-profit-pct: 3.0
  default-breakeven-threshold-pct: 2.0
  default-stop-loss-pct: -2.0
  sell-cost-rate: 0.0025                  # 매수가 = 원가 × (1+이값)
```

---

## 12. 로깅 / 관측

Logback + `logstash-logback-encoder` JSON. MDC에 `cycleId`, `orderId`, `stockCode` 자동 (`MdcContextElement`로 코루틴 경계). 필수 이벤트: 명령 접수/거부, 매수/매도 발송/체결/실패, 시그널 발동, WS 연결/끊김, 토큰 발급, 사이클 종료. 파일 회전 일별 90일.

---

## 13. 테스트 전략

- **단위**: trading.domain (룰 / 시그널 / 상태 전이), TradingValidator. Kotest FunSpec + MockK.
- **통합**: Testcontainers MySQL + WireMock KIS REST + 임베디드 WS fixture. 시나리오 — 정상 사이클 / 손절 / 본전 매도 (재발동) / 취소 (in-flight 포함) / 부분/미체결 / WS 끊김 / 주문 타임아웃 reconcile / 장 마감 강제 청산.
- **수동 검증** (실거래 진입 전): 1주 단위 sanity check.
- **커버리지 목표**: 도메인 90%+, 인프라 70%+.

---

## 14. 운영

### 14.1 시스템 다운/재시작
자동 복구 없음. 시작 시 활성 명령(INITIATED/BUYING/HOLDING/LIQUIDATING) → `close_reason=UNCLOSED` 일괄 마감 + lifecycle CLOSED 발행 → 모니터링 "오늘 종료" + 실적에 자연 노출. (단, 거래정지로 인한 UNCLOSED는 KIS HTS 수동 정리)

### 14.2 로컬 환경
단일 사용자 / 단일 머신. `bootJar` → systemd/launchd 자동 시작/재시작. Frontend Vite 빌드 → 백엔드 정적 서빙. MySQL `mysqldump` 일별 cron.

### 14.3 미해결
시크릿 저장소 (현재 환경변수), KIS 종목명 검색 (KRX 마스터 캐싱 후속), 토큰 자동 갱신 + 동적 tokenStatus 노출.

---

## 15. 구현 우선순위

1. 인프라 부트스트랩 (백엔드 MySQL/로깅/Coroutine, 프론트 Vite)
2. KIS REST 어댑터
3. 도메인 코어 (단위 테스트)
4. OrderExecutor + KIS 주문
5. MarketDataStream
6. TradingCycle 통합
7. REST API + 명령 검증
8. STOMP 푸시 + 프론트 실시간
9. 실적 / 리포트
