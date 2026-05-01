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

## Phase 1: 도메인 코어 + 단위 테스트

KIS 없이도 모든 매매 룰을 검증 가능한 상태로 만든다. 외부 의존 mock.

### 산출물
- `trading.domain.cycle`
  - `CycleState`: sealed class (`Initiated` / `Buying` / `Holding` / `Liquidating` / `Closed`)
  - 전이 규칙 함수: spec §5.2 표 그대로
- `trading.domain.signal`
  - `Signal`: sealed class (StopLoss / MidwayTakeProfit / TpStage / Breakeven / TrendBreak / LimitUp / MarketClose / Cancel)
  - `Signal.isAlive` — 각 시그널이 직접 override (재시도 중단 조건)
- `trading.domain.cycle.CycleSnapshot`
  - 시그널 탐지: `detectSignals(tick, currentBar?, prevBar?)` — StopLoss 우선 처리, 보유=0 가드
  - 트리거 판정 메서드: `isStopLossTriggered`, `isMidwayTakeProfitTriggered`, `isTpStageTriggered`, `isBreakevenTriggered`, `isTrendBreakTriggered`
  - 분할 매도 수량: `splitSellQty` — 절사 + 잔여 처리
- `trading.domain.execution`
  - `Execution` + `Executions` (일급 컬렉션)
  - **매수가 산정**: `Executions.calculateBuyPrice(sellCostRate)` — `(Σ(가 × 수) + Σ수수료) / Σ수 × (1 + sellCostRate)`

### 검증 시나리오 (단위 테스트)
1. **정상 사이클**: 3회 매수 → 2%/3%/5% 단계 발동 → 추세 꺾임 잔여 매도 → Closed (TAKE_PROFIT)
2. **중도 익절 (옵션 A)**: 매수 2회차 직후 +3.5% → 충족 단계 합산 40% 즉시 매도 → 잔여로 Holding 진입
3. **갭상승 복수 단계**: 시초가 +6% → 2%/3%/5% 한 번에 60% 매도
4. **손절 우선순위**: TpStage 무장 + 가격 -2% 동시 → StopLoss 우선
5. **본전 매도 무장 후 발동**: +2% 도달 → 무장 → 매수가 도달 → 전량 매도
6. **추세 꺾임 + 봉 종료**: 무장 후 발동된 봉이 끝나면 isAlive=false → 다음 봉 재충족 시 재발동
7. **NO_FILL**: 3회 모두 미체결 → 보유 0 → Closed(NO_FILL) 직행
8. **시그널 평가 보류**: 보유 수량 = 0 동안 가격 기반 시그널 평가 안 됨

### Definition of Done
- 위 8개 시나리오 + 각 트리거 식 단위 테스트 통과
- 도메인 레이어 커버리지 90%+
- KIS / DB / 시간 의존성 모두 mock 또는 `Clock` 추상화

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

## Phase 3: 영속화 + 명령 접수 API

사용자가 명령을 **데이터로만** 보낼 수 있는 상태. 백그라운드 매매는 아직 없음.

### 산출물
- JPA 엔티티 + Repository (도메인 모델 ↔ 엔티티 매핑은 application 계층에서). 스키마는 Hibernate `ddl-auto=update` 로 자동 생성/갱신.
- `command.domain.CommandValidator`: 입력값 / 1주 가격 / 잔고 / 거래일 / 컷오프 / 중복 / 시간
- `command.application.CommandService`: 접수 / 취소 오케스트레이션
- REST 엔드포인트:
  - `POST /api/commands`
  - `DELETE /api/commands/{id}` (LIQUIDATING 멱등, CLOSED 거부)
  - `GET /api/commands?status=active|today`
  - `GET /api/commands/{id}` (응답 DTO 전체, `activeSell`은 null)
  - `GET /api/account/balance`
  - `GET /api/system/status`
  - `GET /api/stocks/search`, `GET /api/stocks/{code}/price`
- 거부 사유 errorCode 매핑 (PRD 거부 사유 표 8건 + ALREADY_CLOSED)

### 검증 시나리오 (통합 테스트, Testcontainers MySQL + WireMock KIS)
- 거부 케이스 8개 errorCode 검증
- 다중 종목 동시 접수 시 잔고 차감 누적
- DELETE 상태별 응답 (BUYING / MONITORING / LIQUIDATING / CLOSED)
- 컷오프 시각 검증 (시계 mock)

### Definition of Done
- 위 통합 테스트 통과
- Phase 5 프론트 통합 시 mock → 실 API로 1:1 교체 가능 (DTO 일치)

---

## Phase 4: 매매 사이클 엔진 (백엔드 자동 매매)

가장 복잡하고 위험한 단계. 명령 접수 시 자동으로 매수/시그널/매도가 동작.

### 산출물 (4단계 통합)

**4a. OrderExecutor**
- 매수 회차 처리 (시장가 + 부분 체결 + 발송 실패 시 스킵)
- 매도 재시도 루프 (5초 간격, 시그널별 isAlive 가드)
- 멱등성: `clientOrderId = UUID` + 타임아웃 시 일별 체결 조회로 reconcile
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

---

## Phase 5: 실시간 푸시 + 프론트 통합

목표: 사용자가 화면에서 실시간으로 사이클 추적.

### 산출물 (백엔드)
- Spring WebSocket + STOMP (`/ws` 엔드포인트)
- `StatusBroadcastHandler`: 도메인 이벤트 → 토픽 발행
- 토픽 페이로드 (spec §11.5):
  - `/topic/commands/{id}`: PRICE / STATE / SIGNAL / EXECUTION / RETRY
  - `/topic/commands/lifecycle`: CREATED / CLOSED
  - `/topic/system`: MARKET_MODE / TOKEN_STATUS / HOLIDAY / BALANCE_INVALIDATED
- BALANCE_INVALIDATED는 lifecycle CREATED/CLOSED와 함께 자동 발행

### 산출물 (프론트)
- `@stomp/stompjs` 클라이언트 + 자동 재연결 + STOMP 끊김 시 사용자 안내
- TanStack Query 도입 → mock 제거, 실 API 호출로 전환
- React Hook Form + Zod로 매매 명령 화면 폼 검증
- 모니터링 구독 흐름 (spec §11.5 프론트 구독 흐름 그대로)
- 종료 토스트: setTimeout mock → lifecycle CLOSED 이벤트 핸들러로 교체
- 시스템 상태 배지: GET /api/system/status 초기 + /topic/system 푸시 갱신
- 매매 명령 화면: BALANCE_INVALIDATED 수신 시 잔고 재조회

### Definition of Done
- 수동 검증: 명령 접수 → 모니터링 화면에서 1초 내 반영
- 자동 검증: STOMP 페이로드 통합 테스트 (각 토픽 1건씩)
- 프론트 빌드 0 에러 + 빈 mock 디렉토리 (사용 안 함)

---

## Phase 6: 실적 + 운영 인지 채널 마무리

알림 채널이 v1엔 없으므로 모든 운영 정보를 화면에 노출.

### 산출물 (백엔드)
- `report.infrastructure.repository.DailyReportQuery` (집계 SQL)
- `GET /api/reports/daily?date=YYYY-MM-DD` (closeReason 포함)
- 시작 시 활성 명령 → UNCLOSED 마감 + `BALANCE_INVALIDATED` 발행
- 토큰 갱신 실패 / 시세 모드 변경 감지 → `/topic/system` 발행

### 산출물 (프론트)
- ReportPage 실데이터화 + 일자 선택
- "오늘 종료" 미니 섹션 실데이터 (실적 API에서 today 필터)
- 매도 재시도 정보 노출 (CommandDetail.activeSell, 3회 이상 강조)
- UNCLOSED / NO_FILL 행 강조

### Definition of Done
- 일별 합계 정확도 (수수료 + 세금 + 순수익 합산)
- 시작 hook이 활성 명령을 정확히 UNCLOSED로 마감
- 시세 / 토큰 / 휴장 배지가 시스템 상태 변경에 1초 내 반응

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
                             Phase 5 (STOMP + 프론트 통합)
                                     │
                                     ▼
                             Phase 6 (실적 + 인지 채널)
                                     │
                                     ▼
                             Phase 7 (로컬 실행 시작)
```

Phase 1 / 2는 병렬 가능. 그 외는 순차. Phase 4까지는 사용자에게 노출되는 변화 없음 — Phase 5에서 처음으로 화면이 살아남.

---

## 단계별 핵심 위험과 완화

| Phase | 핵심 위험 | 완화 |
|-------|---------|------|
| 1 | 룰 모호성 (단위 테스트 통과해도 의도와 다를 수 있음) | PRD/spec 매매 룰을 시나리오 테스트 1:1 매핑 (위 8개 + 추가) |
| 2 | KIS API 응답 포맷 변경 | WireMock 응답을 실제 KIS 응답 캡쳐로 만들고 버전 기록 |
| 3 | 시각/거래일 의존 검증 어려움 | `Clock` 추상화 + 시계 mock으로 시각 의존 검증 |
| 4 | 코루틴/Mutex 동시성 버그 (실거래에서 가장 위험) | 통합 테스트로 다중 종목 동시 운용 + 반복 실행으로 race 검출 |
| 5 | STOMP 재연결 / 메시지 유실 | 재연결 시 REST로 보강 fetch (spec §11.5에 명시된 흐름 그대로) |
| 6 | UNCLOSED 자동 마감 누락으로 사용자 인지 못 함 | 시작 시 활성 명령 카운트 로깅 + lifecycle CLOSED 이벤트로 모니터링 "오늘 종료" 섹션에 자연 노출 |
| 7 | 통합 테스트 ≠ 실제 KIS 응답 (체결가, 슬리피지, 수수료/세금 산식) | 1주 단위 최소 금액으로 1회 실거래 sanity check 후 룰 동작 재검증 |

---

## 참고

- 본 계획은 [PRD](./prd.md)의 모든 요구사항과 [spec.md](./spec.md)의 기술 명세를 1:1 커버한다.
- Phase 진행 중 새 결정사항이 생기면 PRD/spec을 우선 수정하고 계획서는 그 다음에 업데이트.
- spec.md §16의 9단계 우선순위를 본 문서의 7개 Phase로 재구성한 것이며, 우선순위 자체에 변경은 없음.
