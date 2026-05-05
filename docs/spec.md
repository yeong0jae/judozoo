# SPEC: 주도주 자동 트레이딩 시스템 기술 명세

본 문서는 [PRD](./prd.md)의 요구사항을 구현하기 위한 기술 명세서다. PRD가 "무엇을"을 정의한다면, 본 문서는 "어떻게"를 정의한다.

---

## 1. 시스템 아키텍처

### 1.1 시스템 구성

```
┌──────────────────────────────────────────────────────────┐
│  Frontend (TypeScript + React)                           │
│   - 매매 명령 입력 / 취소                                  │
│   - 진행 중 명령 + 일별 실적 조회                           │
│   - WebSocket(STOMP)으로 실시간 상태 수신                   │
├──────────────────────────────────────────────────────────┤
│  Backend (Kotlin + Spring Boot 4)                        │
│                                                          │
│   Package by feature: 도메인별로 분할하고 도메인 내부에     │
│   필요한 계층만 둔다.                                       │
│                                                          │
│   도메인:                                                  │
│    - trading  : 매매 사이클 접수/취소/조회 + 엔진(상태머신/시그널/주문/룰) │
│    - market   : 시세 스트림 (WS + REST polling fallback)  │
│    - report   : 일별 실적 집계                             │
│    - stock    : 종목 검색                                  │
│    - kis      : KIS 공통 인프라 (인증/REST/WS/RateLimit)   │
│    - common   : 공유 유틸 (예외/시간/MDC/WS push)          │
│                                                          │
│   각 도메인 내부 계층:                                      │
│    presentation → application → domain ← infrastructure  │
└──────────────────────────────────────────────────────────┘
```

### 1.2 주요 설계 원칙

- **Package by feature**: 도메인별로 분할(`trading` / `market` / `report` / `stock` / `kis` / `common`)하고 도메인 안에 `presentation` / `application` / `domain` / `infrastructure` 계층을 배치. 도메인 간 결합은 `application` 계층끼리만
- **Spring Web MVC + Kotlin Coroutines**: webflux 미사용. presentation 계층은 동기 처리, 백그라운드 매매 사이클은 Coroutine Scope에서 비동기 실행
- **종목별 독립 실행**: 트레이딩 사이클당 별도 코루틴 + Mutex로 격리. 다른 사이클에 영향 없음
- **이벤트 기반 시그널 처리**: 시세 스트림(`Flow<PriceTick>`) → SignalDetector → 명령 매칭 → OrderExecutor
- **시그널 감지와 주문 실행 분리**: SignalDetector는 발동 의도만 발신, OrderExecutor가 KIS 호출/재시도 담당
- **상태 머신**: Kotlin `sealed class`로 상태/시그널 표현. 컴파일러가 케이스 누락 강제 검사
- **멱등성**: KIS 발급 ODNO(`Order.kisOrderNo`) 기반 추적 + 타임아웃/통보 누락 시 KIS 일별 체결 조회로 reconcile (개인 계좌 제약상 client-supplied 멱등키 미사용)

---

## 2. 기술 스택

### 2.1 Backend

| 분류 | 선택 | 근거 |
|------|------|------|
| 언어 | Kotlin 2.2.21 | sealed class / null safety / coroutines |
| 런타임 | JVM 21 | 가상 스레드(Project Loom) 활용 가능 |
| 프레임워크 | Spring Boot 4.0.6 + Web MVC | 안정적 동기 모델, 이미 초기화됨 |
| 비동기 | kotlinx.coroutines | webmvc 위에서 IO 작업을 코루틴으로 |
| 영속성 | Spring Data JPA + Hibernate | 이미 의존성 추가됨. 스키마는 `ddl-auto=update`로 자동 관리 |
| DB | MySQL 8 | PRD 결정. mysql-connector-j 의존성 추가됨 |
| HTTP 클라이언트 | Spring `RestClient` | KIS REST 호출 (Spring 6.1+ 표준) |
| WebSocket 클라이언트 | OkHttp WebSocket | KIS 실시간 시세/체결 통보 수신 |
| WebSocket 서버 | Spring WebSocket + STOMP | 프론트로 상태 푸시 |
| JSON | Jackson Module Kotlin | 이미 의존성 추가됨 |
| 검증 | jakarta.validation | 입력값 검증 (의존성 추가 필요) |
| 로깅 | SLF4J + Logback (JSON encoder) | 구조화 로그 |
| 시간 처리 | `java.time` + `Asia/Seoul` zoneid | 거래 시간 정확성 |
| 테스트 | JUnit 5 + MockK + Testcontainers + WireMock | Kotlin 친화 + KIS 모킹 |

### 2.2 Frontend

| 분류 | 선택 |
|------|------|
| 언어 | TypeScript |
| 프레임워크 | React 18+ |
| 빌드 | Vite |
| 상태/데이터 | TanStack Query (서버 상태) + Zustand (간단한 UI 상태) |
| 라우팅 | React Router |
| UI | Tailwind CSS + shadcn/ui |
| 폼 검증 | React Hook Form + Zod |
| WebSocket | `@stomp/stompjs` (Spring STOMP 호환) |
| 테스트 | Vitest + React Testing Library |

---

## 3. 프로젝트 구조

### 3.1 Backend (`backend/src/main/kotlin/at/backend/`)

도메인 우선(package by feature). 각 도메인 안에서 필요한 계층(`presentation` / `application` / `domain` / `infrastructure`)만 둔다. 모든 도메인이 모든 계층을 가질 필요는 없다 — 예: `kis`는 인프라성이라 `domain`이 얇다.

```
at.backend/
├── BackendApplication.kt
│
├── trading/                          # 매매 사이클 전체 (접수/취소/조회 + 엔진)
│   ├── presentation/
│   │   ├── TradingController.kt      # POST/DELETE/GET /api/trading
│   │   └── request/                  # CreateTradingRequest
│   ├── application/
│   │   ├── TradingService.kt         # 검증 / 접수 / 취소 오케스트레이션
│   │   ├── TradingQueryService.kt    # 활성/오늘/상세 조회
│   │   ├── TradingValidator.kt       # 입력값 / 잔고 / 거래일 / 컷오프 검증
│   │   ├── CycleOrchestrator.kt      # 가격 이벤트/시그널 → 사이클 라우팅
│   │   └── result/                   # TradingCreatedResult, TradingSummaryResult, ...
│   ├── domain/
│   │   ├── TradingInput.kt           # 트레이딩 생성 입력 값 객체
│   │   ├── TradingValidationException.kt
│   │   ├── AlreadyClosedException.kt
│   ├── domain/
│   │   ├── cycle/
│   │   │   ├── TradingCycle.kt       # JPA 엔티티 + 도메인 로직 (상태 전이 / 시그널 감지 / 매수가 산정)
│   │   │   ├── TradingCycleStatus.kt # enum (INITIATED/BUYING/HOLDING/LIQUIDATING/CLOSED)
│   │   │   └── CloseReason.kt        # enum (TAKE_PROFIT/STOP_LOSS/…)
│   │   ├── order/
│   │   │   └── Order.kt              # JPA 엔티티 (주문)
│   │   ├── execution/
│   │   │   └── Execution.kt          # JPA 엔티티 (체결)
│   │   ├── price/
│   │   │   ├── Bar.kt                # 3분봉 값 객체
│   │   │   └── PriceTick.kt          # 실시간 호가 값 객체
│   │   └── signal/
│   │       └── Signal.kt             # sealed class + isAlive
│   └── infrastructure/
│       ├── repository/
│       │   ├── TradingCycleJpaRepository.kt
│       │   ├── OrderJpaRepository.kt
│       │   └── ExecutionJpaRepository.kt
│       └── scheduler/
│           └── TradingSchedulerService.kt   # @Scheduled (15:20 청산 / 영업일 게이트)
│
├── market/                           # 시세 스트림 (사용자 노출 없음)
│   ├── application/
│   │   └── MarketDataStream.kt       # WS 구독 + REST polling fallback, Flow<PriceTick>
│   └── infrastructure/
│       └── BarCache.kt               # 3분봉 캐시 (메모리)
│
├── report/                           # 일별 실적 집계
│   ├── presentation/
│   │   ├── ReportController.kt
│   │   └── dto/
│   ├── application/
│   │   └── ReportService.kt
│   └── infrastructure/
│       └── repository/DailyReportQuery.kt   # 집계 쿼리
│
├── stock/                            # 종목 검색
│   ├── presentation/
│   │   └── StockSearchController.kt
│   └── application/
│       └── StockSearchService.kt
│
├── kis/                              # KIS 공통 인프라 (다른 도메인이 의존)
│   ├── application/
│   │   └── KisAuthService.kt         # 토큰 발급/갱신
│   └── infrastructure/
│       ├── KisRestClient.kt
│       ├── KisWebSocketClient.kt
│       ├── KisRateLimiter.kt
│       └── dto/                      # KIS API 응답 DTO
│
├── config/                           # Spring 설정 (도메인 횡단)
│   ├── KisProperties.kt              # @ConfigurationProperties
│   ├── TradingProperties.kt
│   ├── CoroutineConfig.kt            # ApplicationCoroutineScope Bean
│   ├── WebSocketConfig.kt            # STOMP 엔드포인트
│   └── SchedulingConfig.kt           # @EnableScheduling
│
└── common/                           # 공유 유틸 (모든 도메인이 의존)
    ├── error/                        # 공통 예외
    ├── log/                          # MDC 헬퍼 (코루틴 경계 가로지름)
    ├── time/                         # KST clock provider
    └── messaging/
        └── StatusBroadcastHandler.kt # STOMP 푸시 (도메인 간 공통)
```

#### 의존 방향 규칙

- `domain`은 같은 도메인 내 다른 계층에 의존하지 않는다 (순수 도메인 모델/룰만 포함)
- `application`은 같은 도메인의 `domain`과 다른 도메인의 `application`(또는 `infrastructure`의 인터페이스)에 의존 가능
- `presentation`은 같은 도메인의 `application`에만 의존
- `infrastructure`는 같은 도메인의 `domain`을 구현/저장 (역방향 의존 금지)
- `kis`, `common`은 다른 도메인이 의존하는 공유 모듈. 반대로 도메인을 의존하지 않음
- 도메인 간 호출은 `application` 계층끼리만 (예: `TradingService` → `MarketDataStream`)

### 3.2 Frontend (`frontend/`)

```
frontend/
├── package.json
├── vite.config.ts
├── tsconfig.json
└── src/
    ├── main.tsx
    ├── App.tsx
    ├── api/                          # REST 클라이언트 (TanStack Query)
    ├── ws/                           # STOMP 클라이언트
    ├── pages/
    │   ├── TradingPage.tsx           # 매매 명령 화면 (입력 폼 + 검증 컨텍스트)
    │   ├── MonitoringPage.tsx        # 진행 중 모니터링 (리스트 + 상세 뷰)
    │   └── ReportPage.tsx            # 실적 조회 화면
    ├── features/
    │   ├── trading/
    │   ├── monitoring/               # 활성 사이클 리스트 + 단일 사이클 상세
    │   └── report/
    ├── components/
    └── lib/
```

---

## 4. 데이터 모델 (MySQL 스키마)

### 4.1 `trading_cycles`

| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGINT AUTO_INCREMENT | PK |
| stock_code | VARCHAR(10) | 종목 코드 |
| stock_name | VARCHAR(50) | 종목명 |
| per_buy_amount | BIGINT | 1회 매수 금액 (원) |
| buy_interval_min | INT | 추가 매수 간격 (분) |
| split_sell_ratio | DECIMAL(4,3) | 분할 매도 비율 |
| midway_profit_pct | DECIMAL(5,3) | 중도 익절 기준 (%) |
| breakeven_threshold_pct | DECIMAL(5,3) | 본전 매도 트리거 상승률 (%) |
| stop_loss_pct | DECIMAL(5,3) | 손절 기준 (%, 음수) |
| status | VARCHAR(20) | INITIATED / BUYING / HOLDING / LIQUIDATING / CLOSED |
| close_reason | VARCHAR(30) | NULL / TAKE_PROFIT / STOP_LOSS / BREAKEVEN / TREND_BREAK / MARKET_CLOSE / CANCELLED / NO_FILL / UNCLOSED |
| buy_attempt | INT | 현재 매수 회차 (0~3) |
| breakeven_armed | BOOLEAN | +본전기준% 도달 여부 |
| trend_break_armed | BOOLEAN | +5% 도달 여부 |
| tp_stages_fired | INT | 비트 플래그 (b0=2%, b1=3%, b2=5%) |
| created_at | DATETIME(3) | (BaseEntity) |
| updated_at | DATETIME(3) | (BaseEntity) |
| closed_at | DATETIME(3) | NULL 가능 |

> **status 정의**:
> - INITIATED: 검증 통과, 1차 매수 직전
> - BUYING: 매수 회차 진행 중 (1~3차)
> - HOLDING: 3회 매수 시도 완료, 시그널 대기
> - LIQUIDATING: 청산 시그널 발동, 매도 진행 중
> - CLOSED: 사이클 종료

> `TradingCycle` JPA 엔티티가 이 테이블을 직접 매핑하며 도메인 로직(상태 전이 / 시그널 감지 / 매수가 산정)을 함께 보유한다. `holdingQty`·`buyPrice`는 `orders`/`executions`에서 집계하여 파라미터로 전달한다.

### 4.2 `orders`

| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGINT AUTO_INCREMENT | PK |
| cycle_id | BIGINT | FK → trading_cycles |
| side | VARCHAR(4) | BUY / SELL |
| trigger | VARCHAR(20) | BUY_TRY_1/2/3 / TP_2PCT/3PCT/5PCT/REMAINDER / MIDWAY_TP / BREAKEVEN / STOP_LOSS / MARKET_CLOSE / CANCEL |
| order_qty | INT | 주문 수량 |
| filled_qty | INT | 누적 체결 수량 |
| order_type | VARCHAR(10) | 항상 'MARKET' |
| kis_order_no | VARCHAR(20) | KIS 주문번호 |
| status | VARCHAR(15) | PENDING / SUBMITTED / PARTIAL / FILLED / CANCELLED / FAILED |
| retry_count | INT | |
| last_error | VARCHAR(500) | NULL 가능 |
| created_at | DATETIME(3) | (BaseEntity) |
| updated_at | DATETIME(3) | (BaseEntity) |

### 4.3 `executions`

| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGINT AUTO_INCREMENT | PK |
| order_id | BIGINT | FK → orders |
| executed_qty | INT | |
| executed_price | INT | 원 |
| fee | INT | 수수료 |
| tax | INT | 세금 (매도 시) |
| created_at | DATETIME(3) | (BaseEntity) |
| updated_at | DATETIME(3) | (BaseEntity) |

### 4.4 인덱스
- `trading_cycles(status, stock_code)` — 활성 사이클 조회
- `trading_cycles(created_at)` — 일별 리포트
- `orders(cycle_id)` — 사이클 단위 주문 조회
- `executions(order_id)` — 주문별 체결 조회

### 4.5 스키마 관리
- Hibernate `spring.jpa.hibernate.ddl-auto=update`로 엔티티 정의에서 자동 생성/갱신
- destructive 변경(컬럼 삭제/이름 변경)은 v1 운영 진입 시점에 별도 정책 결정

---

## 5. 매매 사이클 상태 머신

### 5.1 상태 표현

`TradingCycleStatus` enum이 상태를 나타낸다. 전이 규칙은 `TradingCycle.canTransitionTo()`에 캡슐화되어 있다.

```kotlin
enum class TradingCycleStatus {
    INITIATED, BUYING, HOLDING, LIQUIDATING, CLOSED
}
```

`buyAttempt`(현재 회차)와 `closeReason`은 `TradingCycle` 엔티티 필드로 관리한다.

### 5.2 전이 다이어그램

```
              ┌───────────┐
              │ Initiated │
              └─────┬─────┘
                    ▼ (1차 매수 시도 시작 — 발송 성공/실패 무관)
              ┌───────────┐
              │  Buying   │ ◄── 회차 대기 / 부분 체결 누적
              └─────┬─────┘
                    │
       ┌────────────┼────────────┬────────────┐
       ▼            ▼            ▼            ▼
   3회완료      손절/취소     3회완료      취소/3회완료
   /중도익절    (보유>0)      (보유=0)     (보유=0)
   (보유>0)                   NO_FILL      CANCELLED
       │            │            │            │
       ▼            ▼            └─────┬──────┘
  ┌──────────┐  ┌──────────────┐       │ (Liquidating
  │ Holding  │  │  Liquidating │       │  거치지 않고 직행)
  └─────┬────┘  └──────┬───────┘       │
        │ ▲            │               │
        │ └─ TpStage   │               │
        │    부분 매도 │               │
        │    (잔여>0)  │               │
        │              │               │
        ▼              │               │
   BE/TB/LU/MC ───────►│               │
   (잔여 전량 매도)    │               │
                       ▼               ▼
                  ┌─────────────────────┐
                  │       Closed        │
                  └─────────────────────┘
```

**전이 규칙 (PRD 정합)**:

| From → To | 트리거 | close_reason |
|-----------|--------|--------------|
| `Initiated → Buying` | 1차 매수 시도 시작 시점 (발송 성공/실패와 무관) | - |
| `Buying → Holding` | (a) 3회 시도 완료 + 보유 > 0, **또는** (b) 중도 익절 발동 + 보유 > 0 | - |
| `Buying → Liquidating` | 손절 발동 OR 사용자 취소 (보유 > 0) | (매도 후 결정) |
| `Buying → Closed` (직행) | (a) 3회 시도 완료 + 보유 = 0, (b) 사용자 취소 + 보유 = 0 | `NO_FILL` / `CANCELLED` |
| `Holding → Holding` (자기 루프) | TpStage(2/3/5%) 부분 매도. 잔여 > 0 유지 | - |
| `Holding → Liquidating` | Breakeven / TrendBreak / LimitUp / MarketClose 발동 (잔여 전량 매도 의도) | (매도 후 결정) |
| `Liquidating → Closed` | 매도 체결 완료 (보유 = 0) | 발동 시그널에 따름 |

> 중도 익절은 *청산이 아니라 조기 Holding 진입*. 충족된 TP 단계는 Monitoring 진입 직후 §6.2 TpStage 시그널이 즉시 매도.

> **TpStage가 Liquidating으로 가지 않는 이유**: 분할 비율로 *부분* 매도이므로 잔여 보유분이 남는다. 잔여는 BE/TB/LU/MC가 처리. 따라서 Holding 안의 자기 루프로 표현.

### 5.3 동시성
- 명령별 `Mutex` 보유. 시그널 매칭 → 상태 전이 → 주문 발송은 모두 락 안에서 수행
- 다른 명령(다른 종목)은 독립적인 코루틴 — 락 경합 없음
- 코루틴은 단일 `ApplicationCoroutineScope`(SupervisorJob + Dispatchers.IO) 아래에서 실행

---

## 6. 시그널 감지 (SignalDetector)

### 6.1 시그널 표현

```kotlin
sealed class Signal {
    abstract val priority: Int
    
    data object StopLoss : Signal()        { override val priority = 1 }
    data object MidwayTakeProfit : Signal() { override val priority = 2 }
    data class TpStage(val pct: Int) : Signal() { override val priority = 2 }
    data object Breakeven : Signal()        { override val priority = 2 }
    data object TrendBreak : Signal()       { override val priority = 2 }
    data object LimitUp : Signal()          { override val priority = 2 }
    data object MarketClose : Signal()      { override val priority = 0 }  // 최우선
    data object Cancel : Signal()           { override val priority = 0 }
}
```

### 6.2 감지 조건

| 시그널 | 트리거 조건 | 발동 가능 상태 |
|--------|-------------|----------------|
| StopLoss | 현재가 ≤ 매수가 × (1 + stop_loss_pct) | Buying / Holding (보유 > 0) |
| MidwayTakeProfit | 현재가 ≥ 매수가 × (1 + midway_profit_pct) | Buying (회차 < 3, 보유 > 0) |
| TpStage(2/3/5) | 현재가 ≥ 매수가 × (1 + N/100) AND `tp_stages_fired` 미발동 | Holding |
| Breakeven | `breakeven_armed` AND 현재가 ≤ 매수가 | Holding |
| TrendBreak | `trend_break_armed` AND 현재 3분봉 종가 < 1전봉 시가 | Holding (잔여 보유분). 봉이 끝나면 isAlive=false로 매도 중단, 다음 봉에서 조건 재충족 시 재발동. |
| LimitUp | 현재가 = 상한가 | Holding (잔여 보유분) |
| MarketClose | KST 시각 ≥ 15:20:00 | 모든 활성 상태 (보유 = 0이면 매도 없이 즉시 Closed) |
| Cancel | 사용자 취소 요청 | INITIATED / BUYING / HOLDING (보유 = 0이면 매도 없이 즉시 Closed=CANCELLED) |

> **보유 수량 = 0 동안 가격 기반 시그널 평가 보류**: 매수가가 정의되지 않으므로 StopLoss / MidwayTakeProfit / TpStage / Breakeven / TrendBreak는 평가 자체를 스킵한다. 첫 부분 체결 발생 시점부터 평가 시작 (PRD §주요 산식 §시그널 평가 전제).

### 6.3 우선순위 처리
- 동일 틱에서 복수 시그널 발동 시 정렬: `MarketClose, Cancel > StopLoss > 그 외`
- StopLoss 발동 시 다른 모든 시그널은 자동 비활성화 (전량 청산이므로 자연 소멸)

### 6.4 상태성(Stateful) 시그널 영속화
- `breakeven_armed`, `trend_break_armed`, `tp_stages_fired`는 commands 테이블에 저장
- 가격 진동으로 같은 트리거가 반복 발동되는 것을 방지
- 영속화 시점: 시그널 무장(armed=true)으로 전이될 때마다 즉시 UPDATE

### 6.5 추세 꺾임 데이터 부족 처리
- KIS 3분봉 응답에서 직전봉이 없으면(`bars.size < 2`) TrendBreak 판정 보류
- 09:00~09:03 사이 +5% 도달 시 잔여 수량은 LimitUp 또는 MarketClose까지 보유

---

## 7. 주문 실행 (OrderExecutor)

### 7.1 매수 회차 처리 (의사 코드)

```kotlin
suspend fun executeBuyTry(cycle: TradingCycle, attempt: Int) {
    val price = marketData.currentPrice(cycle.stockCode)
    val qty = (cycle.perBuyAmount / price).toInt()
    val order = ordersRepo.create(
        commandId = cycle.id,
        side = BUY, type = MARKET, qty = qty,
        trigger = OrderTrigger.buyTry(attempt)
    )
    try {
        kis.submitOrder(order)
    } catch (e: KisException) {
        // 발송 실패: 회차 스킵. 상태는 BUYING 유지, 다음 회차는 예정 시각에 정상 시도.
        order.markFailed(e.message)
        log.warn("Buy attempt $attempt 발송 실패", e)
        return
    }

    val outcome = waitSettlement(order, timeout = 5.seconds)
    when (outcome) {
        is Filled -> { /* 정상 */ }
        is Partial, is Pending -> kis.cancelRemainder(order)  // 부분 체결 보유, 잔량 취소
    }
}
```

> **상태 진입 시점**: `Initiated → Buying` 전이는 1차 매수 *시도 시작 시점*에 발생 (발송 성공/실패와 무관). 1차 발송이 실패해도 BUYING 유지하며 2/3차 회차를 기다린다.

### 7.2 매도 시그널 처리 (재시도 루프)

```kotlin
suspend fun executeSell(cycle: TradingCycle, signal: Signal, intentQty: Int) {
    while (true) {
        if (!signalGuard.isAlive(signal, cycle)) return

        // B-3 충돌 방지: 매도 quantity = intentQty - 진행 중 매도 주문의 미체결 수량
        // 다른 시그널의 매도 주문이 in-flight 중이면 그 미체결분만큼 차감.
        val inFlightUnfilled = ordersRepo.inFlightSellUnfilled(cycle.id)
        val effectiveQty = (intentQty - inFlightUnfilled).coerceAtLeast(0)
        if (effectiveQty == 0) {
            // 다른 매도가 이미 동일 보유분을 처리 중. 이번 시그널은 단발 발동만 기록하고 종료.
            return
        }

        val order = ordersRepo.create(side = SELL, type = MARKET, qty = effectiveQty,
                                      trigger = signal.toTrigger())
        try {
            kis.submitOrder(order)
            val outcome = waitSettlement(order, timeout = 5.seconds)
            if (outcome is Filled) return
        } catch (timeout: TimeoutException) {
            reconcileFromKis(order)  // 멱등성: 체결 조회 후 재판단
        }

        delay(5.seconds)
    }
}
```

> **부분 매도 진행 중 추가 시그널 충돌 방지**: TpStage 매도(예: 20주 부분 매도) in-flight 중에 StopLoss/MarketClose 등 우선순위 0/1 시그널이 발동하면, 새 매도의 quantity = 보유 - 진행 중 미체결분으로 자동 산정되어 *서로 다른 보유분*에 대해 매도. 동일 보유분 중복 매도 방지는 quantity 차감(`effectiveQty = intent - inFlightUnfilled`)으로 보장되며, 추가로 reconcile 1회로 통보 누락도 흡수.

> **재시도 누적 가시성 (PRD §모니터링 매도 재시도 상태)**: 매도 실패가 누적되는 동안 `orders.retry_count` 와 `orders.last_error`를 매 시도마다 업데이트하고, `/topic/trading/{id}` 로 `EXECUTION` 또는 별도 `RETRY` 이벤트를 발행하여 사용자가 상세 뷰에서 진행 상태를 인지할 수 있게 한다.

### 7.3 시그널 생존 판정 (`SignalGuard.isAlive`)

| 시그널 | isAlive 조건 |
|--------|--------------|
| StopLoss | 항상 true (한 번 발동하면 끝까지) |
| Cancel | 항상 true |
| Breakeven | `현재가 ≤ 매수가` 유지 시 true |
| TrendBreak | 발동된 그 봉이 아직 진행 중일 때만 true |
| MarketClose | 항상 true |
| TpStage | 항상 true (분할 매도는 청산 의도) |
| MidwayTakeProfit | 항상 true |

### 7.4 멱등성 / 중복 방지

**KIS 멱등키 미사용 (개인 계좌 제약).** KIS `order-cash`의 client-supplied 멱등키 `gt_uid`는 법인(`custtype=B`) 전용이고, 본 시스템은 개인 계좌(`P`)이므로 사용 불가. 또한 `gt_uid`는 응답에서 echo되지 않아 ODNO 회수에는 어차피 reconcile 필요.

→ **`Order.kisOrderNo`(KIS 발급 ODNO) + `inquire-daily-ccld` reconcile** 기반 설계.

#### 정상 경로
1. `submitOrder` 호출 → 응답 `ODNO`를 `Order.kisOrderNo`에 저장
2. WebSocket `H0STCNI0` 통보 수신 → `kisOrderNo`로 `Order` 조회 → `filledQty` 누적, `Execution` INSERT

#### 타임아웃 / WS 통보 누락 시 reconcile (1회)
1. `GET /inquire-daily-ccld` 호출
2. 매칭 우선순위:
   - `kisOrderNo`가 있으면(응답은 받음, 통보만 누락) → `ODNO` 직접 비교
   - `kisOrderNo`가 없으면(응답 자체가 누락) → 시간 윈도우 ±30초 + 종목 + side + 수량 매칭
3. 매칭 1건 → `kisOrderNo` 갱신, 체결 정보 `Execution` INSERT, 재시도 안 함
4. 매칭 0건 → 새 주문 발송 (매수 회차 스킵 또는 매도 재시도 진행)
5. 매칭 2건+ → 보수적 처리 (`Order.status = NEEDS_MANUAL_REVIEW`, 운영 인지 채널로 알림). Phase 4 흐름상 같은 종목·수량을 30초 내 중복 발송할 일이 거의 없음 (매수 회차는 buyIntervalMin 분 간격, 매도는 B-3 충돌 방지로 quantity가 차감되며 변동).

### 7.5 부분 체결 처리
- KIS 실시간 체결 통보(WebSocket)로 부분 체결 누적
- `orders.filled_qty`를 통보 수신 시마다 증분 갱신
- 주문 종료 판정: `filled_qty + cancelled_qty == order_qty`

### 7.6 매수가 (Average Cost) 계산

PRD §매수가 산정에 따라 **매수 비용 + 예상 매도 비용**을 모두 반영한 손익 0 기준가:

```
매수 원가평균 = (Σ(executed_price × executed_qty) + Σ buy_fee) / Σ executed_qty
매수가         = 매수 원가평균 × (1 + sellCostRate)
```

- `sellCostRate`: `trading.sell-cost-rate` 설정값 (기본 `0.0025` = 0.25%, 매도 거래세 0.18% + 매도 수수료 + 버퍼)
- §6.2의 모든 시그널 트리거(StopLoss / MidwayTakeProfit / TpStage / Breakeven / TrendBreak)는 이 **매수가**를 단일 기준으로 사용. 트리거 식 자체는 변경 없음 — 매수가 정의에 매도 비용률이 곱해져 있기 때문.
- KIS API의 "평균매입단가"(원가 기준)와는 다른 값. UI/응답 DTO는 이 매수가(`averageBuyPrice`)를 사용하고, KIS 원가는 별도 필드로 노출 시 명시 구분.
- 새 체결이 발생할 때마다 `executions` 누적 후 재계산하여 cycle in-memory + commands 테이블에 갱신.

### 7.7 NO_FILL 처리

3회 매수 시도 완료 시점에 누적 체결 수량 = 0이면:
- Liquidating 단계 거치지 않고 즉시 Closed 전이
- `commands.close_reason = NO_FILL`, `closed_at = now()`
- STOMP `/topic/trading/{id}` 으로 `STATE` 이벤트(`status=CLOSED, closeReason=NO_FILL`) 발송

---

## 8. KIS API 통합

### 8.1 인증 (`KisAuthService`)
- 앱 시작 시 AppKey + AppSecret으로 액세스 토큰 발급
- 토큰 유효시간(24h) 기록, 만료 5분 전 자동 재발급 (Spring `@Scheduled`)
- 메모리 보관, 재시작 시 재발급

### 8.2 시크릿 보관 (PRD 결정 사항)
- v1: `application-local.yaml` 또는 환경변수 (`KIS_APP_KEY`, `KIS_APP_SECRET`, `KIS_ACCOUNT_NO`)
- 추후 결정: macOS Keychain / OS 시크릿 저장소 / Spring Cloud Vault 검토

### 8.3 사용 엔드포인트 매핑

| 용도 | KIS 엔드포인트 | 책임 컴포넌트 |
|------|----------------|---------------|
| 토큰 발급 | `POST /oauth2/tokenP` | KisAuthService |
| 영업일 조회 | `GET /uapi/domestic-stock/v1/quotations/chk-holiday` | TradingSchedulerService |
| 종목 검색 | `GET /uapi/domestic-stock/v1/quotations/search-stock-info` | StockSearchController |
| 현재가 조회 | `GET /uapi/domestic-stock/v1/quotations/inquire-price` | MarketDataStream (폴링 fallback) |
| 분봉 조회 | `GET /uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice` | SignalDetector (TrendBreak) |
| 매수/매도 주문 | `POST /uapi/domestic-stock/v1/trading/order-cash` | OrderExecutor |
| 주문 정정/취소 | `POST /uapi/domestic-stock/v1/trading/order-rvsecncl` | OrderExecutor |
| 잔고 조회 | `GET /uapi/domestic-stock/v1/trading/inquire-balance` | TradingValidator (검증) |
| 일별 체결 내역 | `GET /uapi/domestic-stock/v1/trading/inquire-daily-ccld` | OrderExecutor (멱등성) |
| 실시간 시세 (WS) | TR `H0STCNT0` | KisWebSocketClient |
| 실시간 체결 통보 (WS) | TR `H0STCNI0` | KisWebSocketClient |

### 8.4 Rate Limit (`KisRateLimiter`)
- 실거래 REST: 초당 20건 제한
- Bucket4j 또는 Resilience4j RateLimiter로 토큰 버킷 구현
- 모든 KIS REST 호출은 `KisRateLimiter.acquire()` 통과 후 발신

---

## 9. 시세 데이터 처리 (`MarketDataStream`)

### 9.1 정상 흐름
1. 명령 등록 시 `MarketDataStream.subscribe(stockCode)` 호출
2. KIS WebSocket에 해당 종목 구독 추가
3. 체결가 통보 수신 → `Flow<PriceTick>` 으로 발행
4. SignalDetector / 활성 명령들이 Flow를 collect
5. 명령 종료 시 `unsubscribe(stockCode)` (해당 종목 다른 활성 명령 없는 경우)

### 9.2 끊김 / Fallback
- `KisWebSocketClient`가 onClose 이벤트 수신 시 재연결 백오프 (1s → 2s → 5s → 5s …)
- 재연결 시도 중인 동안 모든 활성 종목에 대해 1초 간격 REST 폴링으로 현재가 조회 → 동일 `Flow` 로 발행
- 재연결 성공 시 폴링 중단, WS 시세로 자동 복귀
- Frontend에는 별도 STOMP 채널로 "WS 끊김 / 폴링 모드" 상태 푸시

### 9.3 3분봉 데이터
- TrendBreak 판정용. WebSocket이 아닌 REST로 주기 조회 (예: 30초마다)
- 캐시 후 봉이 새로 닫혔을 때만 SignalDetector에 푸시

---

## 10. 스케줄러 (`TradingSchedulerService`)

### 10.1 명령별 타이머
- 1차 매수 직후: `delay(buyIntervalMin.minutes)` 코루틴으로 2차 매수 트리거
- 2차 매수 직후: `delay(buyIntervalMin.minutes)` 코루틴으로 3차 매수 트리거
- 3차 매수 직후: 타이머 종료, Holding 상태 진입

> 타이머는 `TradingCycle` 코루틴 안에서 `delay()`로 직접 처리. 별도 스케줄러 불필요.

### 10.2 글로벌 스케줄
- `@Scheduled(cron = "0 20 15 * * MON-FRI", zone = "Asia/Seoul")`: MarketClose 시그널 일제 발행
- `@Scheduled(cron = "0 0 8 * * MON-FRI", zone = "Asia/Seoul")`: 영업일 검증 + 명령 접수 게이트 토글

### 10.3 신규 명령 컷오프 검증
- `TradingService.create()`에서 현재 KST 시각과 컷오프(`15:20 - buyIntervalMin × 2`) 비교
- 컷오프 이후면 `TradingValidationException(CUTOFF_PASSED)` → 400 응답

---

## 11. REST API (UI 통신)

### 11.1 트레이딩 관리

```
POST /api/trading
  Request: {
    stockCode, perBuyAmount, buyIntervalMin?, splitSellRatio?,
    midwayProfitPct?, breakevenThresholdPct?, stopLossPct?
  }
  Response 201: { commandId, status }
  Response 400 errorCode:
    - STOCK_NOT_FOUND
    - PRICE_BELOW_ONE_SHARE
    - INSUFFICIENT_BALANCE
    - DUPLICATE_COMMAND
    - CUTOFF_PASSED
    - HOLIDAY
    - OUT_OF_TRADING_HOURS
    - INVALID_PARAMETER

DELETE /api/trading/{id}
  - status = INITIATED / BUYING / HOLDING:
      Response 202: { status: "LIQUIDATING" } (보유 > 0)
                    또는 { status: "CLOSED", closeReason: "CANCELLED" } (보유 = 0, 직행)
  - status = LIQUIDATING:
      Response 202: { status: "LIQUIDATING" } (멱등 처리, 진행 중 청산 유지)
  - status = CLOSED:
      Response 409 errorCode: "ALREADY_CLOSED"

GET /api/trading?status=active|today
  Response 200: TradingSummary[] (status=active)
              | DailyTrading[]   (status=today)

  TradingSummary: {
    commandId, stockCode, stockName,
    status,                              // INITIATED / BUYING / HOLDING / LIQUIDATING
    currentPrice, averageBuyPrice,
    profitRate, profitAmount,
    holdingQty,
    buyAttempt: { completed: 0..3, total: 3 }
  }

GET /api/trading/{id}
  Response 200: TradingDetail

  TradingDetail = TradingSummary + {
    totalBoughtQty,
    tpStages: { fired2pct, fired3pct, fired5pct },     // 비트플래그 디코딩
    splitSellProgress: { soldPct },                    // 누적 매도 비율 (0~100)
    breakevenArmed, trendBreakArmed,
    closeReason,                                       // nullable
    createdAt, closedAt,
    // 트레이딩 파라미터 (입력값 그대로)
    perBuyAmount, buyIntervalMin, splitSellRatio,
    midwayProfitPct, breakevenThresholdPct, stopLossPct,
    // 매도 재시도 가시성 (PRD §모니터링 운영 항목)
    activeSell: {
      signalType,         // 진행 중 매도 시그널 (LIQUIDATING 상태에서만)
      retryCount,         // 누적 재시도 횟수
      lastError           // 마지막 KIS 에러 메시지 (nullable)
    } | null,
    // 이력
    orders: [{ id, side, trigger, status, orderQty, filledQty,
               submittedAt, settledAt, retryCount, lastError }],
    executions: [{ orderId, executedQty, executedPrice, fee, tax, executedAt }]
  }
```

> **상태별 필드 가시성**: BUYING 상태에서 `tpStages` / `trendBreakArmed`는 항상 `false` (3회 매수 완료 전엔 무장 안 됨). 프론트는 PRD의 "상태별 노출 규칙"에 따라 회색 처리.

### 11.2 실적 조회
```
GET /api/reports/daily?date=YYYY-MM-DD
  Response 200: [{
    commandId, stockCode, stockName,
    boughtAmount, soldAmount,
    fee, tax,
    netProfit, profitRate,
    closeReason     // TAKE_PROFIT / STOP_LOSS / BREAKEVEN / TREND_BREAK /
                    // MARKET_CLOSE / CANCELLED / NO_FILL / UNCLOSED
  }]
```

> UI는 `closeReason = UNCLOSED` 또는 `NO_FILL` 행에 배지/색상 강조하여 사용자가 인지하도록 표시 (PRD §실적 조회 화면).

### 11.3 종목 검색 / 시세 조회
```
GET /api/stocks/search?q=keyword
  Response 200: [{ stockCode, stockName }]

GET /api/stocks/{stockCode}/price
  Response 200: { stockCode, currentPrice, asOf }
  // 매매 명령 화면의 "선택 종목 현재가" / "예상 매수 수량 계산"용.
```

### 11.4 계좌 / 시스템 조회
```
GET /api/account/balance
  Response 200: { cashBalance, reservedAmount, availableBalance }
  - cashBalance: KIS 가용 예수금 (raw)
  - reservedAmount: Σ(활성 명령의 미진행 매수 잔여)
                  = Σ(per_buy_amount × (3 - 완료된 매수 회차))
  - availableBalance: cashBalance - reservedAmount
  // 매매 명령 화면의 "사용 가능한 잔고"용.

GET /api/system/status
  Response 200: {
    marketMode: "WS" | "POLLING",      // 시세 모드 배지
    tokenStatus: "OK" | "REFRESH_FAILED",   // KIS 토큰 갱신 상태
    isHoliday,
    tradingHoursOpen,
    cutoffPassed                       // 신규 명령 컷오프 도달 여부
  }
  // 모니터링 화면 시스템 상태 배지의 초기값 + 매매 명령 화면의 명령 가능 여부 산출용.
  // UNCLOSED 명령은 별도 카운트가 아니라 lifecycle CLOSED 이벤트로 모니터링 "오늘 종료" 섹션에 노출.
```

### 11.5 실시간 상태 푸시 (STOMP)
```
WS endpoint: /ws

Topics:
  /topic/trading/{id}        - 단일 명령 이벤트 (모니터링 상세 뷰 / 리스트 행 갱신용)
  /topic/trading/lifecycle   - 명령 생성/종료 (모니터링 리스트의 행 추가/제거용)
  /topic/market              - 시세 모드 변경, 휴장 토글
  /topic/account             - 잔고 변동 가능성 알림
```

#### 페이로드

```
/topic/trading/{id}:
  { type: "PRICE",     currentPrice, profitRate, profitAmount, ts }
  { type: "STATE",     status, closeReason?, ts }
  { type: "SIGNAL",    signalType, event: "ARMED" | "FIRED", stage?, ts }
  { type: "EXECUTION", side, qty, price,
                       totalFilledQty, holdingQty, averageBuyPrice, ts }
  { type: "RETRY",     signalType, retryCount, lastError, ts }   // 매도 재시도 누적

/topic/trading/lifecycle:
  { type: "CREATED", commandId, stockCode, stockName, ts }
  { type: "CLOSED",  commandId, closeReason, ts }

/topic/market:
  { type: "MARKET_MODE", mode: "WS" | "POLLING", ts }
  { type: "HOLIDAY",     isHoliday, ts }

/topic/account:
  { type: "BALANCE_INVALIDATED", ts }   // 잔고 변동 가능성 알림 — 매매 명령 화면이
                                        // GET /api/account/balance 재조회를 트리거
```

> `BALANCE_INVALIDATED`는 lifecycle CREATED/CLOSED 이벤트와 함께 발행. 매매 명령 화면 입력 중 잔고가 stale되는 것을 막는다 (PRD §매매 명령 화면 자동 갱신).

#### 프론트 구독 흐름 (모니터링 화면)

1. 페이지 진입: `GET /api/system/status` + `GET /api/trading?status=active` → 시스템 상태 배지 + 리스트 렌더
2. `/topic/trading/lifecycle` 구독 → CREATED 이벤트 시 행 추가, CLOSED 시 행 제거 + **종료 토스트** 1회 표시 (PRD §종료 인지)
3. 활성 명령마다 `/topic/trading/{id}` 구독 → 행/상세 뷰 갱신 (PRICE / STATE / SIGNAL / EXECUTION / RETRY)
4. `/topic/market` 구독 → 시세 모드 / 휴장 배지 갱신
5. 상세 뷰 진입: `GET /api/trading/{id}` 로 보강 데이터(이력 + activeSell) 로드

#### 프론트 구독 흐름 (매매 명령 화면)

1. 페이지 진입: `GET /api/system/status` + `GET /api/account/balance` → 명령 가능 여부 + 잔고 표시
2. `/topic/account` 구독 → `BALANCE_INVALIDATED` 수신 시 잔고 재조회
3. `/topic/market` 구독 → 휴장 변동 시 화면 비활성화 갱신
4. 종목 선택 시 `GET /api/stocks/{stockCode}/price` → 현재가 + 예상 매수 수량 계산

---

## 12. 설정 (`application.yaml`)

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/autonomous_trading
    username: ${DB_USER}
    password: ${DB_PASSWORD}
  jpa:
    hibernate.ddl-auto: update
    open-in-view: false

kis:
  app-key: ${KIS_APP_KEY}
  app-secret: ${KIS_APP_SECRET}
  account-no: ${KIS_ACCOUNT_NO}
  account-product-code: ${KIS_ACCOUNT_PRODUCT_CODE}
  base-url: https://openapi.koreainvestment.com:9443
  ws-url: ws://ops.koreainvestment.com:21000
  rate-limit-per-second: 20

trading:
  market-close-time: "15:20"
  default-buy-interval-min: 3
  default-split-sell-ratio: 0.20
  default-midway-profit-pct: 3.0
  default-breakeven-threshold-pct: 2.0
  default-stop-loss-pct: -2.0
  sell-cost-rate: 0.0025               # 매수가 = 원가 × (1 + 본 값). 매도 거래세 + 수수료 + 버퍼.

logging:
  level:
    root: INFO
    at.backend: DEBUG
```

---

## 13. 로깅 / 관측

### 13.1 구조화 로그
- Logback + `logstash-logback-encoder`로 JSON 출력
- MDC에 `commandId`, `orderId`, `stockCode` 자동 주입 (`MdcContextElement`로 코루틴 경계 가로지르기)

### 13.2 필수 로그 이벤트
- 명령 접수 / 거부 (사유)
- 매수 회차 발송 / 체결 / 실패
- 시그널 발동 (signal_type, trigger_price, position_qty)
- 매도 발송 / 체결 / 재시도
- WebSocket 연결 / 끊김 / 재연결
- 토큰 발급 / 갱신
- 사이클 종료 (close_reason)

### 13.3 보관
- 파일 회전: 일별 (`logs/trading-YYYY-MM-DD.log`), 90일 보관

---

## 14. 테스트 전략

### 14.1 단위 테스트 (JUnit 5 + MockK)
- `trading.domain.rule.TradingRules`: 분할 매도 수량 계산, 매수가 산정, 절사/잔여 처리
- `trading.domain.signal.SignalDetector`: 각 시그널 트리거 조건, 우선순위, 상태성 영속화
- `trading.domain.cycle.TradingCycle`: 상태 전이 규칙, 잘못된 전이 차단
- `trading.application.TradingValidator`: 입력값 / 잔고 / 거래일 / 컷오프 검증

### 14.2 통합 테스트
- **DB**: Testcontainers MySQL
- **KIS REST**: WireMock으로 응답 시나리오 재현
- **KIS WebSocket**: 임베디드 WS 서버 fixture로 시세/체결 통보 재현
- 시나리오:
  - 정상 매매 사이클 (3회 매수 → 익절 단계 발동 → 추세 꺾임 → 청산)
  - 손절 시나리오
  - 본전 매도 시나리오 (재발동 포함)
  - 취소 시나리오 (in-flight 매수 포함)
  - 부분 체결 / 미체결 처리
  - WebSocket 끊김 + REST 폴링 fallback
  - 주문 타임아웃 + 체결 조회 후 멱등 처리
  - 장 마감 강제 청산

### 14.3 수동 검증 (실거래 시작 전)
- 1주 단위 최소 금액으로 정상 사이클 1회 sanity check (Phase 4 통합 테스트가 다룬 시나리오와 동일하게 동작하는지 KIS 실응답에서 확인)

### 14.4 커버리지 목표
- 도메인 레이어: 90% 이상
- 인프라 레이어: 70% 이상

---

## 15. 운영 고려사항

### 15.1 시스템 다운 / 재시작 (PRD 명시)
- 자동 복구 없음 — 진행 중이던 매매 사이클을 재시작 후 이어가지 않는다
- 시작 시 status가 INITIATED/BUYING/HOLDING/LIQUIDATING인 명령은 모두 `close_reason=UNCLOSED` + `closed_at=now()`로 일괄 마감
- 마감된 명령은 lifecycle CLOSED 이벤트로 발행되어 모니터링 화면 "오늘 종료" 섹션 + 실적 화면에 자연스럽게 노출
- 사용자 별도 알림/조치 불필요 (단, §7 거래정지로 인한 UNCLOSED는 KIS에 보유분이 남으므로 별도 — 사용자가 KIS HTS에서 수동 정리)

### 15.2 로컬 실행 환경
- 단일 사용자 / 단일 머신 (개인 PC). 외부 노출이나 다중 사용자 고려 없음.
- 백엔드: `./gradlew bootJar` → systemd/launchd로 fat jar 실행 (PC 부팅 시 자동 시작 / 크래시 시 자동 재시작)
- 프론트엔드: Vite 빌드 → 백엔드에서 정적 서빙 (별도 호스팅 불필요)
- DB 백업: MySQL `mysqldump` 일별 cron

### 15.3 미해결 / 추후 결정
- 시크릿 저장소 (현재 환경변수)
- 다음 영업일 미청산 종목 처리 자동화 (현재 수동)
- 월별 실적 화면

---

## 16. 구현 우선순위 (MVP 순서)

1. **인프라 부트스트랩**
   - 백엔드: MySQL 연결, 로깅(JSON), Coroutine Scope Bean
   - 프론트엔드: Vite 초기화, Tailwind, 라우팅
2. **KIS REST 어댑터**: 인증, 현재가/잔고 조회, 영업일 조회 (단위 테스트 포함)
3. **도메인 코어 (단위 테스트 동반)**: TradingRules, Signal, CycleState (KIS 호출 없이 순수 로직)
4. **OrderExecutor + KIS 주문 통합**: 매수/매도, 체결 조회, 멱등성
5. **MarketDataStream**: KIS WebSocket + REST 폴링 fallback
6. **TradingCycle 통합**: 시그널 → 실행기 → 상태 전이 end-to-end
7. **REST API + 명령 검증**: 잔고/거래일/입력값 검증, 명령 접수/취소
8. **STOMP 푸시 + 프론트 실시간 화면**
9. **실적 조회 / 리포트**

각 단계는 통합 테스트로 검증 후 다음 단계 진입.
