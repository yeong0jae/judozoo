# tasks-007 — Phase 5-B-1: UI/UX 재설계

Goal: 백엔드가 제공하는 실 DTO 형태로 mock을 재작성하고, 그 위에서 화면 UI/UX를 다시 설계한다. 데이터는 여전히 mock — 실 API/STOMP 연결은 다음 phase(tasks-008).

> 기존 mock은 백엔드 DTO 확정 전 추측으로 만들어진 상태. 두 번 일하지 않기 위해 UI/UX를 먼저 완성하고 mock의 shape를 실 DTO와 1:1 일치시킨 뒤, 다음 phase에서 wire-up.

---

## 디자인 원칙

- **인지 채널 다층화**: 사이클 종료를 토스트만으로 알리지 않음. 탭 제목 / OS 알림 / 헤더 종 / 토스트 / 종료 리스트 5중 레이어.
- **버튼 비활성 사유 자명성**: 모든 차단 조건은 진단 가능한 위치에 명시.
- **백엔드 사실 우선**: SummaryResult에 없는 필드(예: `breakevenArmed`)는 행에 표시하지 않음. 무장 시각화는 Detail 진입 후만.
- **8가지 closeReason 일관 시각화**: NO_FILL / UNCLOSED는 운영 인지를 위해 강조.

---

## Mock 재작성 (실 DTO shape)

- [x] `mocks/data.ts` — 백엔드 DTO와 1:1 일치하는 mock 재작성
  - `SystemStatus { marketMode, tokenStatus, isHoliday, tradingHoursOpen, cutoffPassed }`
  - `AccountBalance { cashBalance, reservedAmount, availableBalance }`
  - `TradingSummary { commandId, stockCode, stockName, status, currentPrice, averageBuyPrice, profitRate(소수), profitAmount, holdingQty, buyAttempt: { completed, total } }`
  - `TradingDetail` (Summary + tpStages, splitSellProgress, breakevenArmed, trendBreakArmed, perBuyAmount, buyIntervalMin, splitSellRatio, midwayProfitPct, breakevenThresholdPct, stopLossPct, orders[], executions[], closeReason, createdAt, closedAt)
  - `DailyTrading { commandId, stockCode, stockName, status, closeReason, profitRate, profitAmount, createdAt, closedAt }`
  - `StockSearchResult { stockCode, stockName }` / `StockPriceResult { stockCode, currentPrice, asOf }`
  - STOMP 페이로드 시뮬레이터 (PRICE/STATE/SIGNAL/EXECUTION/RETRY, lifecycle CREATED/CLOSED, market MODE/HOLIDAY, account BALANCE_INVALIDATED) — 임의 발화 가능

## 공통 기반

- [x] `lib/format.ts` 확장 — formatKRW / formatPct / formatQty / formatDateTime / formatTime / formatDuration / formatRelative
- [x] `lib/errorMessages.ts` — 9개 errorCode + ALREADY_CLOSED + NOT_FOUND + Network/5xx 한글 매핑
- [ ] 공통 컴포넌트 — `StatusPill` (TradingCycleStatus), `CloseReasonBadge` (8종), `ProfitText` (양/음/0 색상), `Skeleton`, `EmptyState`, `ErrorState`

## 레이아웃 / 헤더 / 인지 채널

- [ ] `components/layout/Header` — 좌측 탭(매매명령/모니터링/실적) + 우측 (알림종 / 시스템배지 / STOMP배지 / 설정⚙)
- [ ] `SystemStatusBadge` 재설계 — 5조건 진단 팝오버 (우선순위: 토큰>휴장>거래시간>컷오프>시세모드)
- [ ] `StompStatusBadge` — 연결됨/재연결중/끊김 + 팝오버
- [ ] `NotificationBell` — 미확인 카운트 + 최근 이벤트 팝오버 + lastSeenAt 관리 (localStorage)
- [ ] `StompDisconnectionBanner` — 끊김 시 노란 띠, 복구 시 녹색 1.5s
- [ ] `Toast` 시스템 — `useToast` hook + 우하단 stacking + 종료 토스트는 closeReason 색
- [ ] 탭 제목 + Favicon 빨간 점 (미확인 ≥ 1)
- [ ] `Settings` 패널 — OS알림 / 사운드 / UNCLOSED 강조 토글 (localStorage)

## 모니터링 화면 재설계

- [ ] 활성 명령 행 — `[StatusPill][종목 + 평단→현재가][수익률 큰글씨][매수 dots][보유수량][🔥 LIQUIDATING만]` (※ 본전/추세 무장 아이콘은 Summary에 데이터 없음 — 행에서 제거)
- [ ] 상세 패널 — 요약헤더 / 매수진행(다음 매수 시각: orders 마지막 BUY + buyIntervalMin) / 시그널무장보드(tpStages 3단계 + 본전 + 추세) / 분할매도 progress / 주문이력 / 체결이력
- [ ] 오늘 종료 섹션 — closeReason 8종 일관 시각화, NO_FILL/UNCLOSED 행 배경 강조
- [ ] 정렬(수익률/상태/종목명/매수진행) + closeReason 필터
- [ ] 종료 토스트 (mock setTimeout 발화) + 클릭 시 종료 행으로 스크롤
- [ ] 실시간 시각 효과 — PRICE flash / SIGNAL FIRED 펄스 / EXECUTION 인라인 알림 (mock 트리거로 시연)
- [ ] 헤더 "오늘 손익" — DailyTrading.profitAmount 합 (수수료/세금 분리 표기 ❌ — Phase 6에서)
- [ ] 빈 상태 (활성 0건 / 오늘 종료 0건)

## 매매 명령 화면 재설계

- [ ] 차단 배너 — 휴장/거래시간/컷오프/토큰 4가지 조건별 색/문구
- [ ] 시스템 패널 — 5조건 신호등 (배지가 종합, 패널이 진단)
- [ ] 잔고 패널 — cashBalance/reservedAmount/availableBalance 3분할 + BALANCE_INVALIDATED mock 발화 시 fade 갱신
- [ ] 종목 검색 — 디바운스 dropdown, 키보드 ↑/↓/Enter
- [ ] 종목 선택 → 별도 가격 조회 (asOf + 수동 ↻)
- [ ] 1회 매수금액 — 콤마 포맷 + 예상 수량 / 3회 총 예약 / 잔고 한도 / 1주 가격 미달 검증 즉시 표시
- [ ] 고급 설정 ▾ 접이식 — 5개 필드 default placeholder + 변경 시 ● 마커, "기본값으로 리셋"
- [ ] 활성 명령 미리보기 사이드바 — 같은 종목 중복 사전 인지
- [ ] 에러 인라인 표시 위치 — 9개 errorCode를 적절 필드/배너에 라우팅
- [ ] 제출 성공 토스트 — "[모니터링에서 확인 →]" 링크

## 실적 화면 재설계

- [ ] 3 요약 카드 — 순수익(profitAmount 합) / 거래건수+승률(profitAmount > 0 = 승) / closeReason 분포 mini bar (※ 수수료/세금 분리 표시 ❌ — Phase 6)
- [ ] 거래 내역 테이블 — 종료시각/종목/보유시간/수익률/수익금/사유 (※ "매수→매도가" 컬럼은 "—" 표시, Phase 6에서 활성)
- [ ] 정렬(종료시각/수익률) + closeReason 다중 선택 + 손익 토글
- [ ] NO_FILL/UNCLOSED 행 배경 강조 + 페이지 상단 UNCLOSED 알림 배너
- [ ] 드릴다운 패널 — 모니터링 상세 컴포넌트 재사용 (`live: false` prop으로 STOMP 구독 OFF)
- [ ] 날짜 선택기 — 좌우 화살표 + 캘린더 (5-B에서는 오늘 외 disabled + 툴팁 "이전 날짜는 추후 제공")
- [ ] 빈 상태

## 라우팅 / Provider 정리

- [ ] `App.tsx` — `<NotificationProvider>` + `<ToastProvider>` (Mock STOMP 상태는 임시 context로) + Layout 적용
- [ ] mock STOMP 토글 디버그 도구 — 개발 시 토픽 페이로드 임의 발화 (개발 모드 전용)

## Verification

- [ ] `npm run build` 0 에러 / 0 type 에러
- [ ] 4개 화면 (모니터링/명령/실적/공통 헤더) 모든 인터랙션(정렬/필터/토글/팝오버/토스트/드릴다운) 동작
- [ ] 모든 closeReason 8종이 일관된 색/아이콘으로 렌더링됨
- [ ] mock STOMP 발화 도구로 실시간 시각 효과 (flash/펄스/토스트/배지) 시연 가능

---

## Definition of Done

- 위 Verification 통과
- mock의 shape가 백엔드 DTO와 1:1 일치 (필드명/타입)
- 다음 phase(tasks-008)의 wire-up 작업이 "mock import → real query 교체"만으로 완결되는 구조

## 후속 / Phase 5-B-2 (tasks-008)

- 의존성 도입 (@stomp/stompjs / @tanstack/react-query / RHF / Zod)
- API 클라이언트 + 쿼리/뮤테이션 훅
- STOMP Provider + 구독
- mock → real 교체
- 재연결 보강 fetch
