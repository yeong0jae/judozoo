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

## Phase 8: 배포 인프라 ✅
초기 "로컬 실행(systemd/launchd)" 목표는 Docker + GCE 배포로 대체. 백엔드·프론트 도커라이즈(multi-stage, nginx 프록시 `/api`·`/ws`→`backend:8080`), 루트 `docker-compose.yml`/`docker-compose.prod.yml`, Terraform(`asia-northeast3` VM + 고정 IP + Artifact Registry + Secret Manager 12개 + WIF) + GitHub Actions 배포 워크플로우(WIF auth → build·push → scp/ssh → 헬스체크). 자세한 단계는 [`infra/docs/plan.md`](../infra/docs/plan.md), [`infra/docs/task/tasks-001.md`](../infra/docs/task/tasks-001.md).

## Phase 9: Broker 추상화
KIS 직접 호출을 인터페이스 뒤로 숨기고 행동은 보존. `trading.application.broker.BrokerTradingClient` 인터페이스 도출, `platform.kis.adapter.KisBrokerAdapter` 구현으로 기존 호출부(6개: `OrderService`, `TradingService`, `TradingQueryService`, `TradingValidator`, `ExecutionNoticeListener`, `UnclosedCycleStartupHook`) 일괄 교체. 통합 테스트 회귀 0건.

## Phase 10: Kiwoom 어댑터 + Profile 와이어링
`platform.kiwoom.trading.*` 신설 — Kiwoom REST 클라이언트, 주문/잔고/체결 모듈, WireMock 인프라 테스트. `KiwoomBrokerAdapter`로 Phase 9 인터페이스 구현. `application-{kis,kiwoom,vts,real}.yml` 분리, broker 어댑터를 `@Profile`로 분기. 부팅 시 broker·env 각각 정확히 1개 active인지 fail-fast 검증.

## Phase 11: 3-인스턴스 배포
KIS 모의(`kis-vts`) + KIS 실제(`kis-real`) + Kiwoom 실제(`kiwoom-real`) 동시 운영. Terraform `google_compute_instance` / `google_compute_address` / firewall을 `for_each = toset(["kis-vts","kis-real","kiwoom-real"])`로 변환 + `terraform state mv`로 기존 KIS-vts VM destroy 0건 마이그레이션. Secret Manager에 `AT_KIWOOM_ACCOUNT_NO` 등 누락 시크릿 추가, Kiwoom 콘솔에 `kiwoom-real` VM의 외부 IP 등록(`kis-*`는 KIS만 부르므로 등록 불필요). GHA workflow를 `strategy.matrix.target` 3-entry로 변환 — 각 target이 자기 `(broker, env, vm, account)`를 포함, `SPRING_PROFILES_ACTIVE=${BROKER},${ENV}` 주입. 3 VM 동시 배포 + 각각 헬스체크. MySQL named volume은 VM별 독립.

## Phase 12: leadingstock 회귀 안전망
`leadingstock` 도메인 단위 테스트 보강. 필터 14개 (DailyHighPosition / DailyPriceChange / EtfExclusion / MarketCap / MinuteCandleFluctuation / MinuteCandleVolume / OpeningPrice / PrevDayClose / PriceAboveOpen / ProgramNetBuy / ThemeRank / TradingValueRank 등) 각 경계 조건, `FilterChain` 결합 시나리오, `LeadingStockService` / `InvestorTrendService` 흐름. 도메인 룰이라 Spring·MockK 의존 없는 Kotest FunSpec(한글 description). 후속 Kiwoom·trading 변경 시 회귀 가드 역할.

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
                     Phase 8 (배포 인프라)
                         │
                         ▼
                     Phase 9 (Broker 추상화)
                         │
                         ▼
                     Phase 10 (Kiwoom 어댑터 + Profile)
                         │
                         ▼
                     Phase 11 (3-인스턴스 배포)
                         │
                         ▼
                     Phase 12 (leadingstock 회귀 안전망)
```

Phase 1 / 2는 병렬 가능. Phase 5-A까지는 사용자 노출 변화 없음. Phase 9는 행동 보존이므로 외부 변화 없음. Phase 12는 독립 가능 — Phase 9~11과 병렬 진행 가능.

## 주요 위험과 완화

- **도메인 룰 모호성** → PRD 시나리오 1:1 매핑 단위 테스트
- **KIS API 응답 포맷 변경** → WireMock fixture를 실 응답 캡처로 유지
- **코루틴/Mutex 동시성 버그** → 다중 종목 통합 테스트 + 반복 실행
- **STOMP 메시지 유실** → 재연결 시 모든 query invalidate
- **UNCLOSED 인지 못 함** → 시작 hook lifecycle CLOSED 발행 + 모니터링/실적 강조
- **통합 테스트 ≠ 실 KIS 응답** → 1주 단위 실거래 sanity check (Phase 7)
