# 개발 계획: 주도주 자동 트레이딩 시스템

본 문서는 [PRD](./prd.md)와 [spec.md](./spec.md) §16 구현 우선순위를 **Phase 단위**로 구체화한다. 각 Phase는 독립적으로 머지 가능하며, 이전 Phase의 산출물이 다음 Phase의 입력이 된다.

---

## 원칙

1. **위험 우선(de-risking)**: 가장 모호하거나 손실 직결 위험이 큰 부분부터 검증 (매매 룰, KIS 인증)
2. **외부 의존 후행**: 도메인 코어는 KIS 없이 단위 테스트로 검증 → 어댑터/통합은 그 후
3. **테스트가 게이트**: 도메인 90%+, 인프라 70%+ 커버리지를 만족하지 못하면 다음 Phase 진입 금지 (실거래 룰 버그 = 즉시 손실)
4. **각 Phase end-to-end 동작 가능**: 머지 시점에 부분적이라도 동작 가능한 상태. "가짜 코드"로 미완 부분 채우지 말 것

---

## Phase 0: 부트스트랩 (현재 상태)

목표: 빌드/실행이 되는 빈 골조.

| 영역 | 상태 |
|------|------|
| Backend Spring Boot 4 + Kotlin 2.2 + MySQL/JPA 의존성 | ✅ 완료 |
| Frontend Vite + React + TS + Tailwind + Router | ✅ 완료 (mock 데이터 화면) |
| JSON 로깅 (logstash-encoder) + MDC | TODO |
| `KisProperties` / `TradingProperties` / `application-local.yaml` 템플릿 | TODO |
| `ApplicationCoroutineScope` Bean (`CoroutineConfig.kt`) | TODO |

**Definition of Done**:
- `./gradlew bootRun` → 정상 부팅
- `npm run dev` → 5173 포트에서 mock 화면 동작
- JSON 로그가 stdout으로 출력 (포맷 검증)

---

## Phase 1: 도메인 코어 + 단위 테스트 ✅ 완료

KIS 없이도 모든 매매 룰을 검증 가능한 상태로 만든다. 외부 의존 mock.

### 산출물
- `trading.domain.cycle`
  - `TradingCycle`: JPA 엔티티 + 도메인 로직 통합 (`trading_cycles` 테이블)
    - 상태 전이: `canTransitionTo(nextStatus, nextBuyAttempt?, nextCloseReason?)`
    - 시그널 탐지: `detectSignals(tick, holdingQty, buyPrice)` / `detectSignals(tick, currentBar, prevBar, holdingQty, buyPrice)`
    - 트리거 판정: `isStopLossTriggered`, `isMidwayTakeProfitTriggered`, `isTpStageTriggered`, `isBreakevenTriggered`, `isTrendBreakTriggered`
    - 분할 매도 수량: `splitSellQty`
    - 매수가 산정: `calculateBuyPrice(executions, sellCostRate)`
  - `TradingCycleStatus`: enum (INITIATED / BUYING / HOLDING / LIQUIDATING / CLOSED)
  - `CloseReason`: enum (TAKE_PROFIT / STOP_LOSS / BREAKEVEN / TREND_BREAK / MARKET_CLOSE / CANCELLED / NO_FILL / UNCLOSED)
- `trading.domain.order`
  - `Order`: JPA 엔티티 (`orders` 테이블, FK → `trading_cycles`)
- `trading.domain.execution`
  - `Execution`: JPA 엔티티 (`executions` 테이블, FK → `orders`)
- `trading.domain.price`
  - `Bar`: 3분봉 값 객체
  - `PriceTick`: 실시간 호가 값 객체
- `trading.domain.signal`
  - `Signal`: sealed class + `isAlive` (StopLoss / MidwayTakeProfit / TpStage / Breakeven / TrendBreak / LimitUp / MarketClose / Cancel)
- `trading.infrastructure.repository`
  - `TradingCycleJpaRepository`, `OrderJpaRepository`, `ExecutionJpaRepository`
- `library.jpa.BaseEntity`: `@MappedSuperclass` (createdAt / updatedAt)

### 검증 시나리오 (단위 테스트)
1. **정상 사이클**: 3회 매수 → 2%/3%/5% 단계 발동 → 추세 꺾임 잔여 매도 → Closed (TAKE_PROFIT)
2. **중도 익절**: 매수 2회차 직후 +3.5% → Holding 진입
3. **갭상승 복수 단계**: 시초가 +6% → 2%/3%/5% 한 번에 발동
4. **손절 우선순위**: TpStage 무장 + 가격 -2% 동시 → StopLoss 우선
5. **본전 매도 무장 후 발동**: +2% 도달 → 무장 → 매수가 도달 → 전량 매도
6. **추세 꺾임 + 봉 종료**: 무장 후 발동된 봉이 끝나면 isAlive=false → 다음 봉 재충족 시 재발동
7. **NO_FILL**: 3회 모두 미체결 → 보유 0 → Closed(NO_FILL) 직행
8. **시그널 평가 보류**: 보유 수량 = 0 동안 가격 기반 시그널 평가 안 됨

### Definition of Done
- 위 8개 시나리오 + 각 트리거 식 단위 테스트 통과 ✅
- 도메인 레이어 커버리지 90%+
- KIS / DB / 시간 의존성 모두 mock 또는 파라미터 주입

---

## Phase 2: KIS REST 어댑터

KIS와 안정적으로 통신하는 인프라. 단, **주문 제외** — 주문은 Phase 4에서.

### 산출물
- `kis.application.KisAuthService`: 토큰 발급, 만료 5분 전 자동 재발급(`@Scheduled`)
- `kis.infrastructure.KisRestClient` (Spring `RestClient`): 현재가, 영업일, 종목검색, 잔고, 분봉, 일별 체결
- `kis.infrastructure.KisRateLimiter`: 초당 20건 토큰 버킷 (Resilience4j)
- `KisProperties`(@ConfigurationProperties) + `application-local.yaml` 템플릿
- WireMock 기반 통합 테스트 (응답 시나리오 캡쳐)

### Definition of Done
- 정상 / 4xx / 5xx / 타임아웃 / Rate limit 시나리오 통합 테스트 통과
- 토큰 만료 5분 전 갱신 트리거 검증
- 인프라 커버리지 70%+

---

## Phase 3: 명령 접수 API

사용자가 명령을 **데이터로만** 보낼 수 있는 상태. 백그라운드 매매는 아직 없음.

> JPA 엔티티(`TradingCycle`, `Order`, `Execution`)와 Repository는 Phase 1 리팩토링에서 완료. Phase 3은 application/presentation 계층 구현에 집중.

### 산출물
- `trading.application.TradingValidator`: 입력값 / 1주 가격 / 잔고 / 거래일 / 컷오프 / 중복 / 시간 검증
  - `KisRestClient` / `TradingCycleJpaRepository` 주입
  - 거부 시 `TradingValidationException(errorCode)` 발생
- `trading.application.TradingService`:
  - `create()`: 검증 → `TradingCycle(status=INITIATED)` 생성 → `TradingCycleJpaRepository.save()` → 응답 반환
  - `cancel()`: 상태별 취소 처리 (LIQUIDATING 멱등, CLOSED 거부)
- REST 엔드포인트:
  - `POST /api/trading` → 201
  - `DELETE /api/trading/{id}` → 202 / 409
  - `GET /api/trading?status=active|today`
  - `GET /api/trading/{id}`
  - `GET /api/account/balance`
  - `GET /api/system/status`
  - `GET /api/stocks/search`, `GET /api/stocks/{code}/price`
- `GlobalExceptionHandler`: errorCode 매핑 (8건 거부 + ALREADY_CLOSED)

### 검증 시나리오 (통합 테스트, Testcontainers MySQL + WireMock KIS)
- 거부 케이스 8개 errorCode 검증
- 다중 종목 동시 접수 시 잔고 차감 누적 검증
- DELETE 상태별 응답 (BUYING / HOLDING / LIQUIDATING / CLOSED)
- 컷오프 시각 검증 (시계 mock)

### Definition of Done
- 위 통합 테스트 통과
- Phase 5-B-2 프론트 통합 시 mock → 실 API로 1:1 교체 가능 (DTO 일치)

---

## Phase 4: 매매 사이클 엔진 (백엔드 자동 매매)

가장 복잡하고 위험한 단계. 명령 접수 시 자동으로 매수/시그널/매도가 동작.

### 산출물 (4단계 통합)

**4a. OrderExecutor**
- 매수 회차 처리 (시장가 + 부분 체결 + 발송 실패 시 스킵)
- 매도 재시도 루프 (5초 간격, 시그널별 isAlive 가드)
- 멱등성: KIS 발급 `kisOrderNo`(ODNO) 추적 + 타임아웃/통보 누락 시 일별 체결 조회 1회 reconcile (개인 계좌 — client-supplied 멱등키 미지원)
- **B-3 충돌 방지**: 매도 quantity = intent - inFlight 미체결분
- 재시도 누적: `orders.retry_count`, `last_error` 매 시도 갱신

**4b. MarketDataStream**
- `KisWebSocketClient` (OkHttp WebSocket)
- 종목별 구독 / 해제 + 백오프 재연결 (1s → 2s → 5s)
- 끊김 동안 1초 간격 REST 폴링 fallback → 동일 `Flow<PriceTick>` 발행
- `BarCache` (3분봉, 30초마다 폴링)

**4c. TradingCycle + 오케스트레이션**
- `TradingCycle` 코루틴: 명령별 격리 + Mutex
- 상태 전이 (Phase 1 룰 + DB 영속화)
- 시그널 매칭 → OrderExecutor 호출 → 상태 갱신
- `CycleOrchestrator`: PriceTick → 활성 사이클 라우팅
- `TradingSchedulerService`:
  - `@Scheduled cron 0 20 15 * * MON-FRI` MarketClose 일제 발행
  - 매일 영업일 게이트 토글

### 검증 시나리오 (end-to-end 통합 테스트)
- 정상 사이클 (3회 매수 → 단계 발동 → 추세 꺾임 잔여 매도)
- 손절 시나리오
- 본전 매도 (재발동 포함)
- 취소 (in-flight 매수 포함)
- 부분 체결 / 미체결 / NO_FILL
- WebSocket 끊김 + REST 폴링 fallback (시세 모드 전환)
- 주문 타임아웃 + 일별 체결 조회 reconcile
- 다중 종목 동시 운용 (3개 이상)
- 15:20 강제 청산 (분할 익절 후 잔여 포함)

### Definition of Done
- 위 9개 통합 시나리오 통과
- 코루틴 누수 / 락 경합 / Mutex deadlock 검증 (반복 실행)
- 시스템 다운 후 재시작 시 활성 명령 → UNCLOSED 자동 마감 검증

> 비고: 원래 DoD에 "도메인 이벤트 발생점이 STOMP broadcast hook 부착 가능 위치에 정렬됨"이 포함되었으나, 실제 `ApplicationEventPublisher` 도입은 미실시. publisher와 STOMP 인프라는 Phase 5-A에서 함께 구축한다.

---

## Phase 5-A: 백엔드 도메인 이벤트 + STOMP 브로드캐스트

목표: 사이클 흐름의 핵심 분기점에서 도메인 이벤트를 발행하고, STOMP 토픽으로 브로드캐스트하는 안정적 표면을 만든다 (프론트 통합의 입력).

### 산출물 (백엔드)
- 도메인 이벤트 정의 + Spring `ApplicationEventPublisher` 발행 (사이클 흐름 ↔ broadcast 결합도 분리)
- Spring WebSocket + STOMP (`/ws` 엔드포인트) — `library/web/WebSocketConfig`
- 각 feature가 자기 broadcast handler를 가짐 (분산 모델, 별도 broadcast feature 없음):
  - `trading.application.TradingBroadcastHandler` → `/topic/trading/{id}`, `/topic/trading/lifecycle`
  - `market.application.MarketBroadcastHandler` → `/topic/market`
  - `account.application.AccountBroadcastHandler` → `/topic/account`
- 토픽 페이로드 (spec §11.5):
  - `/topic/trading/{id}`: PRICE / STATE / SIGNAL / EXECUTION / RETRY
  - `/topic/trading/lifecycle`: CREATED / CLOSED
  - `/topic/market`: MARKET_MODE / HOLIDAY
  - `/topic/account`: BALANCE_INVALIDATED
- BALANCE_INVALIDATED는 lifecycle CREATED/CLOSED와 함께 자동 발행
- HOLIDAY는 변경 hook이 시스템에 존재하는 경우 발행, 없으면 후속 이슈로 분리

### Definition of Done
- 토픽별 STOMP 통합 테스트 1건씩 통과 (PRICE / STATE / SIGNAL / EXECUTION / RETRY / lifecycle / market / account)
- BALANCE_INVALIDATED가 CREATED/CLOSED와 동반 발행됨을 검증
- spec §11.5 페이로드 스키마 (필드명/타입) 1:1 일치

---

## Phase 5-B-1: 프론트 UI/UX 재설계

목표: 백엔드 DTO 확정 후, 그 형태에 맞춰 mock을 재작성하고 화면 UI/UX를 다시 설계한다. 데이터는 여전히 mock — 실 연결은 5-B-2.

> 기존 mock은 백엔드 DTO 확정 전 추측으로 만들어진 상태. wire-up과 UI 재설계를 동시에 하면 두 번 일하게 되므로 분리.

### 산출물 (프론트)
- 실 DTO shape으로 mock 재작성 (Summary / Detail / Daily / Balance / SystemStatus + STOMP 페이로드)
- 모니터링 화면 — 활성/상세/오늘종료 + closeReason 8종 일관 시각화 + NO_FILL/UNCLOSED 강조
- 매매 명령 화면 — 차단 사유 자명성 + 매수 미리보기 + 고급 설정 토글 + 9 errorCode 라우팅
- 실적 화면 — 요약 카드 + 거래 내역 + 드릴다운 (Phase 6 wire-up 호환 구조)
- **인지 채널 다층화** — 헤더 종 / 탭 제목 / OS 알림 / 사운드 / 토스트 (모든 페이지 상시) — 토스트 단독으로는 화면을 안 보고 있을 때 놓침
- 공통 — Toast / formatter / errorMessages / 색상-아이콘 시스템

### Definition of Done
- npm run build 0 에러
- mock shape가 백엔드 DTO와 1:1 일치
- 다음 phase의 wire-up이 "mock import → real query 교체"만으로 완결되는 구조

---

## Phase 5-B-2: 실 API + STOMP 연결

목표: 5-B-1 위에서 mock을 실 API/STOMP로 교체한다. 화면 변경 없음.

### 산출물 (프론트)
- `@stomp/stompjs` + TanStack Query + React Hook Form + Zod 도입
- API 클라이언트 + ApiError → errorCode 한글 라우팅 + 쿼리/뮤테이션 훅
- StompProvider + per-id `/topic/trading/{id}` 구독 + lifecycle/market/account 구독
- BALANCE_INVALIDATED / HOLIDAY / MARKET_MODE 처리
- NotificationProvider — 인지 채널(종/탭/OS/사운드)을 lifecycle CLOSED와 wire
- 재연결 시 모든 query invalidate + 누락 CLOSED 보강 (todayClosed diff)

### Definition of Done
- 수동 검증 5종 (행 추가 1초 / 종료 토스트 / STOMP 끊김-복구 / 잔고 갱신 / 다른 페이지 인지)
- mocks/ 디렉토리 삭제
- npm run build 0 에러

---

## Phase 6: 실적 + 운영 인지 채널 마무리

알림 채널이 v1엔 없으므로 모든 운영 정보를 화면에 노출.

### 산출물 (백엔드)
- `report.infrastructure.repository.DailyReportQuery` (집계 SQL)
- `GET /api/reports/daily?date=YYYY-MM-DD` (closeReason 포함)
- 시작 시 활성 명령 → UNCLOSED 마감 + `BALANCE_INVALIDATED` 발행
- 토큰 갱신 실패 / 시세 모드 변경 감지 처리 (별도 토픽 추가 여부는 후속 결정 — 현재 시스템은 `/topic/market`의 MARKET_MODE만 발행)

### 산출물 (프론트)
- ReportPage 실데이터화 + 일자 선택
- "오늘 종료" 미니 섹션 실데이터 (실적 API에서 today 필터)
- 매도 재시도 정보 노출 (CommandDetail.activeSell, 3회 이상 강조)
- UNCLOSED / NO_FILL 행 강조

### Definition of Done
- 일별 합계 정확도 (수수료 + 세금 + 순수익 합산)
- 시작 hook이 활성 명령을 정확히 UNCLOSED로 마감
- 시세 모드 / 휴장 배지가 시스템 상태 변경에 1초 내 반응 (토큰 상태는 30s polling으로 갱신)

---

## Phase 7: 로컬 실행 시작

목표: 로컬 머신에서 안정적으로 자동 매매가 돌아가는 상태.

### 작업
1. **실거래 1주 단위 sanity check**
   - 1회 매수 금액 = 현재가 × 1주 (최소 단위)로 정상 사이클 1회 실행
   - Phase 4 통합 테스트가 검증한 룰이 실제 KIS 응답에서도 동일하게 동작하는지 확인 (체결가, 수수료, 세금이 spec과 일치하는지 검증)
2. **로컬 실행 환경 구성**
   - `./gradlew bootJar` → systemd/launchd unit (PC 부팅 시 자동 시작 / 크래시 시 자동 재시작)
   - Frontend: Vite build → 백엔드에서 정적 서빙 (별도 호스팅 불필요)
   - MySQL `mysqldump` 일별 cron
   - 로그 회전 (90일, logback 설정)
3. **운영 체크리스트**
   - 매일 09:00 시작 시: 시스템 상태 배지 / 영업일 / 토큰 / 잔고 확인
   - 매일 15:30 마감 후: 실적 화면 검토 (UNCLOSED·NO_FILL 종목 확인 — 시스템 다운 케이스는 별도 조치 불필요, 거래정지로 인한 UNCLOSED만 KIS HTS에서 수동 정리)
   - 매주: KIS API 응답 변경 / 거래세율 변경 모니터링

### Definition of Done
- 실거래 1주 단위 sanity check 통과 (수익률 / 청산 사유가 의도대로)
- 로컬 실행 후 1일 안정 가동 (자동 시작 / 재시작 동작 확인)

---

## 의존 그래프

```
Phase 0 (부트스트랩, 현재)
   │
   ├─► Phase 1 (도메인 코어) ───────┐
   │                                 │
   └─► Phase 2 (KIS REST 어댑터) ────┤
                                     ▼
                             Phase 3 (영속화 + 명령 API)
                                     │
                                     ▼
                             Phase 4 (사이클 엔진)
                                     │
                                     ▼
                             Phase 5-A (백엔드 STOMP 표면)
                                     │
                                     ▼
                             Phase 5-B-1 (프론트 UI/UX 재설계)
                                     │
                                     ▼
                             Phase 5-B-2 (실 API + STOMP 연결)
                                     │
                                     ▼
                             Phase 6 (실적 + 인지 채널)
                                     │
                                     ▼
                             Phase 7 (로컬 실행 시작)
```

Phase 1 / 2는 병렬 가능. 그 외는 순차. Phase 5-A까지는 사용자에게 노출되는 변화 없음 — Phase 5-B-1에서 처음으로 화면이 살아남.

---

## 단계별 핵심 위험과 완화

| Phase | 핵심 위험 | 완화 |
|-------|---------|------|
| 1 | 룰 모호성 (단위 테스트 통과해도 의도와 다를 수 있음) | PRD/spec 매매 룰을 시나리오 테스트 1:1 매핑 (위 8개 + 추가) |
| 2 | KIS API 응답 포맷 변경 | WireMock 응답을 실제 KIS 응답 캡쳐로 만들고 버전 기록 |
| 3 | 시각/거래일 의존 검증 어려움 | `Clock` 추상화 + 시계 mock으로 시각 의존 검증 |
| 4 | 코루틴/Mutex 동시성 버그 (실거래에서 가장 위험) | 통합 테스트로 다중 종목 동시 운용 + 반복 실행으로 race 검출 |
| 5-A | 도메인 이벤트 발행 누락 / 토픽 페이로드 스키마 불일치 | 토픽별 STOMP 통합 테스트 + spec §11.5 1:1 검증 |
| 5-B-1 | 백엔드 DTO 표면과 mock의 shape 불일치로 5-B-2에서 재작업 | UI 작성 전에 mock을 실 DTO와 1:1로 재작성 |
| 5-B-2 | STOMP 재연결 / 메시지 유실 | 재연결 시 REST로 보강 fetch (모든 query invalidate + todayClosed diff로 누락 CLOSED 보강) |
| 6 | UNCLOSED 자동 마감 누락으로 사용자 인지 못 함 | 시작 시 활성 명령 카운트 로깅 + lifecycle CLOSED 이벤트로 모니터링 "오늘 종료" 섹션에 자연 노출 |
| 7 | 통합 테스트 ≠ 실제 KIS 응답 (체결가, 슬리피지, 수수료/세금 산식) | 1주 단위 최소 금액으로 1회 실거래 sanity check 후 룰 동작 재검증 |

---

## 참고

- 본 계획은 [PRD](./prd.md)의 모든 요구사항과 [spec.md](./spec.md)의 기술 명세를 1:1 커버한다.
- Phase 진행 중 새 결정사항이 생기면 PRD/spec을 우선 수정하고 계획서는 그 다음에 업데이트.
- spec.md §16의 9단계 우선순위를 본 문서의 7개 Phase로 재구성한 것이며, 우선순위 자체에 변경은 없음.
