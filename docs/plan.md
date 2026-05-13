# 개발 계획: 주도주 자동 트레이딩 시스템

[PRD](./prd.md) / [spec.md](./spec.md) §16의 우선순위를 Phase 단위로 구체화한 문서. 각 Phase는 독립 머지 가능, 이전 산출물이 다음 입력.

## 원칙

1. **위험 우선** — 손실 직결 영역(매매 룰, KIS 인증)부터 검증
2. **외부 의존 후행** — 도메인 코어는 단위 테스트로 먼저 닫고, 어댑터/통합은 그 다음
3. **테스트가 게이트** — 도메인 90%+, 인프라 70%+ 미만이면 다음 Phase 진입 금지
4. **각 Phase end-to-end 동작 가능** — 가짜 코드로 미완 채우지 말 것

---

## Phase 0: 부트스트랩 ✅
빌드/실행 가능한 빈 골조. Backend (Spring Boot 4 + Kotlin), Frontend (Vite + React + Tailwind), JSON 로깅, KIS/Trading Properties.

## Phase 1: 도메인 코어 + 단위 테스트 ✅
KIS 없이 모든 매매 룰을 검증. `TradingCycle` JPA 엔티티 + 도메인 로직 통합 (상태 전이 / 시그널 탐지 / 매수가 산정), `Order`, `Execution`, `Bar`, `PriceTick`, `Signal` sealed class, JPA Repository. 8개 핵심 시나리오(정상 사이클, 중도 익절, 갭상승, 손절 우선, 본전 매도, 추세 꺾임, NO_FILL, 보유 0 평가 보류) 단위 테스트.

## Phase 2: KIS REST 어댑터 ✅
`KisAuthService` (토큰 만료 5분 전 자동 재발급), `KisRestClient`, `KisRateLimiter` (초당 20건). WireMock 통합 테스트.

## Phase 3: 명령 접수 API ✅
`TradingValidator` (8가지 errorCode), `TradingService.create/cancel`, REST 엔드포인트 (`POST/DELETE /api/trading`, `GET /api/trading?status=`, `GET /api/account/balance`, `GET /api/system/status`, `GET /api/stocks/...`). Testcontainers MySQL + WireMock 통합 테스트.

## Phase 4: 매매 사이클 엔진 ✅
**OrderExecutor** — 매수 회차 처리, 매도 재시도(5초 간격, isAlive 가드), 멱등성(`kisOrderNo` + reconcile), B-3 충돌 방지. **MarketDataStream** — `KisWebSocketClient` + 백오프 재연결, REST 폴링 fallback, `BarCache`. **TradingCycle 코루틴** — 명령별 격리 + Mutex, `CycleOrchestrator` 라우팅, `TradingSchedulerService` (15:20 청산, 영업일 게이트). 9개 end-to-end 통합 시나리오.

## Phase 5-A: 백엔드 도메인 이벤트 + STOMP ✅
도메인 이벤트 정의 + `ApplicationEventPublisher` 발행. STOMP 엔드포인트 `/ws`, broker `/topic`. Feature별 broadcast handler:
- `/topic/trading/{id}` — PRICE / STATE / SIGNAL / EXECUTION / RETRY
- `/topic/trading/lifecycle` — CREATED / CLOSED
- `/topic/market` — MARKET_MODE / HOLIDAY
- `/topic/account` — BALANCE_INVALIDATED (lifecycle CREATED/CLOSED와 동반)

## Phase 5-B-1: 프론트 UI/UX 재설계 ✅
백엔드 DTO와 1:1 일치하는 mock 재작성. 모니터링/매매명령/실적 화면 전면 재설계. **인지 채널 다층화** — 헤더 종 / 탭 제목 / OS 알림 / 사운드 / 토스트 (모든 페이지 상시). closeReason 8종 일관 시각화 + NO_FILL/UNCLOSED 강조. 공통 컴포넌트(StatusPill / CloseReasonBadge / ProfitText / FlashOnChange / Toast / formatter / errorMessages).

## Phase 5-B-2: 실 API + STOMP 연결 ✅
@stomp/stompjs + TanStack Query + RHF + Zod 도입. API 클라이언트 + 쿼리/뮤테이션 훅. StompProvider + per-id `/topic/trading/{id}` 구독. NotificationsBridge로 lifecycle CLOSED → 인지 채널 wire. 재연결 시 모든 query invalidate. mocks/ 디렉토리 삭제.

## Phase 6: 실적 + 운영 인지 채널 마무리 ✅
`report` feature — `DailyReportResult` + `ReportService` + `GET /api/reports/daily?date=` (수수료/세금/매수→매도가 포함). UnclosedCycleStartupHook 통합 테스트 보강 (lifecycle CLOSED + BALANCE_INVALIDATED 발송 검증). Frontend ReportPage 실 wire + 날짜 선택기 + DetailPanel `ActiveSellAlert` (매도 재시도 ≥3 강조).

## Phase 7: 보유 주식 수동 매도
`account` feature — KIS 잔고 조회(`inquire-balance`)의 실 계좌 보유분을 조회하는 `GET /api/account/holdings` + 종목별 보유 전량 시장가 매도 `POST /api/account/holdings/{code}/sell`. 프론트 "보유 주식" 탭 (리스트 + 행별 "시장가 매도" 버튼 + 확인 다이얼로그). 활성 사이클이 매매 중인 종목은 표시하되 매도 버튼 비활성. 예상치 못한 오류로 사이클이 종료됐지만 KIS 계좌에 포지션이 남은 케이스의 수동 정리 수단.

## Phase 8: 로컬 실행 시작 (보류)
실거래 1주 단위 sanity check, bootJar + systemd/launchd, MySQL 일별 백업, 로그 회전. 운영 체크리스트.

---

## 의존 그래프

```
Phase 0
  ├─► Phase 1 (도메인) ──┐
  └─► Phase 2 (KIS REST)─┤
                         ▼
                     Phase 3 (명령 API)
                         │
                         ▼
                     Phase 4 (사이클 엔진)
                         │
                         ▼
                     Phase 5-A (STOMP 표면)
                         │
                         ▼
                     Phase 5-B-1 (UI/UX 재설계)
                         │
                         ▼
                     Phase 5-B-2 (실 wire)
                         │
                         ▼
                     Phase 6 (실적 + 인지)
                         │
                         ▼
                     Phase 7 (보유 주식 수동 매도)
                         │
                         ▼
                     Phase 8 (로컬 실행)
```

Phase 1 / 2는 병렬 가능. Phase 5-A까지는 사용자 노출 변화 없음.

## 주요 위험과 완화

- **도메인 룰 모호성** → PRD 시나리오 1:1 매핑 단위 테스트
- **KIS API 응답 포맷 변경** → WireMock fixture를 실 응답 캡처로 유지
- **코루틴/Mutex 동시성 버그** → 다중 종목 통합 테스트 + 반복 실행
- **STOMP 메시지 유실** → 재연결 시 모든 query invalidate
- **UNCLOSED 인지 못 함** → 시작 hook lifecycle CLOSED 발행 + 모니터링/실적 강조
- **통합 테스트 ≠ 실 KIS 응답** → 1주 단위 실거래 sanity check (Phase 7)
