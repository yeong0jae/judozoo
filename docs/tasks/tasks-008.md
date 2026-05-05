# tasks-008 — Phase 5-B-2: 실 API + STOMP 연결

Goal: tasks-007에서 완성된 UI/UX 위에 실 백엔드 API + STOMP 토픽을 연결한다. 화면 변경 없음 — 데이터 소스만 mock → real로 전환.

> UI/UX는 [tasks-007.md (Phase 5-B-1)](tasks-007.md) 에서 완료.
> 백엔드 토픽 표면은 [tasks-006.md (Phase 5-A)](tasks-006.md) 에서 완료.

---

## 의존성 / 환경

- [x] `npm install @stomp/stompjs @tanstack/react-query react-hook-form zod @hookform/resolvers`
- [x] Vite dev proxy — `/api`, `/ws` → `http://localhost:8080`

## API 클라이언트 (`src/api/`)

- [x] `client.ts` — fetch wrapper, `ApiResponse<T>` 언래핑, errorCode → `ApiError` 변환
- [x] `queryClient.ts` — TanStack Query 전역 설정 + `App.tsx`에 Provider 부착
- [x] 쿼리 훅 — `useSystemStatus`(30s refetch), `useAccountBalance`, `useActiveCommands`, `useTodayClosed`, `useCommandDetail(id)`, `useStockSearch(q)`, `useStockPrice(code)`
- [x] 뮤테이션 훅 — `useCreateCommand`, `useCancelCommand` — 성공 시 invalidate (실패 시 errorCode 라우팅은 페이지 wire 시 처리)

## STOMP 클라이언트 (`src/ws/`)

- [x] `stompClient.ts` — 단일 `Client` 싱글톤, brokerURL `ws://.../ws`, 자동 재연결 (5s), JSON 파싱
- [x] `StompProvider` — 연결 상태(connected/reconnecting/disconnected) context로 노출 → `StompStatusBadge` / `StompDisconnectionBanner`가 구독
- [x] `useStompSubscription(destination, handler)` — mount/unmount 안전 구독 hook

## 알림 채널 wire (NotificationProvider)

- [x] `/topic/trading/lifecycle` 전역 구독 → CLOSED 이벤트를 NotificationProvider에 누적 (lastSeenAt 이후만)
- [x] 탭 제목 / OS Notification API (Settings 토글 시 권한 1회 요청) / 사운드 — Settings 토글에 따라 분기
- [x] UNCLOSED / NO_FILL은 종 빨강 + OS 알림 `requireInteraction` 강조

## 매매 명령 화면 wire

- [x] mock 제거 → `useSystemStatus` + `useAccountBalance` + `useStockSearch` + `useStockPrice` + `useCreateCommand`
- [x] React Hook Form + Zod 스키마 — perBuyAmount + 5개 advanced 필드 검증
- [x] `/topic/account` 구독 → BALANCE_INVALIDATED 시 `useAccountBalance` invalidate
- [x] `/topic/market` 구독 → HOLIDAY/MARKET_MODE 시 `useSystemStatus` invalidate
- [x] 활성 명령 미리보기 → `useActiveCommands`

## 모니터링 화면 wire

- [x] mock 제거 → `useActiveCommands` + `useTodayClosed` + `useCommandDetail(selectedId)` + `useCancelCommand`
- [x] `/topic/trading/lifecycle` 구독 — CREATED → activeCommands invalidate, CLOSED → activeCommands+todayClosed invalidate + 종료 토스트 (NotificationsBridge와 dedup)
- [x] 활성 명령마다 `/topic/trading/{id}` 구독 — PRICE/STATE는 setQueryData로 부분 갱신, EXECUTION/SIGNAL/RETRY는 detail invalidate
- [x] `/topic/market` MARKET_MODE → 헤더 배지 (전역 invalidate via App.tsx onReconnect 및 NotificationsBridge가 처리)

## 실적 화면 wire

- [ ] mock 제거 → `useTodayClosed`로 오늘 데이터 표시
- [ ] 드릴다운 → `useCommandDetail(selectedId)` (모니터링 컴포넌트 재사용, `live: false`)
- [ ] 날짜 선택기는 disabled 유지 (Phase 6에서 `/api/reports/daily?date=` 도입 시 활성)

## 시스템 상태 배지 wire

- [ ] mock 제거 → `useSystemStatus` 직접 사용
- [ ] `/topic/market` 구독으로 HOLIDAY/MARKET_MODE 부분 invalidate

## 재연결 보강 fetch

- [ ] StompProvider `onConnect` (재연결 포함) → 모든 쿼리 invalidate (active/today/balance/system/detail) → 끊김 동안 누락된 lifecycle CLOSED는 todayClosed diff로 NotificationProvider에 보강 발화

## 정리

- [ ] `frontend/src/mocks/` import 0건 (grep 검증) → 디렉토리 삭제

## Verification

- [ ] `npm run build` 0 에러
- [ ] 수동 검증: 백엔드 + 프론트 동시 기동 → `POST /api/trading` (curl 또는 폼) → 모니터링에 1초 내 행 추가
- [ ] 수동 검증: 사이클 종료 시 종료 토스트 1회 + activeCommands에서 사라지고 todayClosed로 이동 + 헤더 종 배지 +1
- [ ] 수동 검증: 백엔드 재시작으로 STOMP 강제 끊기 → 끊김 배너 → 재연결 후 보강 fetch + 누락 CLOSED 알림 발화
- [ ] 수동 검증: 다른 명령 생성 시 BALANCE_INVALIDATED → 잔고 fade 갱신
- [ ] 수동 검증: 다른 페이지 이동 시에도 헤더 종 배지/탭 제목/OS 알림 동작

---

## Definition of Done

- 위 수동 검증 모두 통과
- `frontend/src/mocks/` 디렉토리 import 0건 + 삭제
- `npm run build` 0 에러

## 후속 / Phase 6 (tasks-009)

- 실적 화면 `/api/reports/daily` wire (날짜 선택기 활성, 수수료/세금 분리, 매수→매도가)
- 매도 재시도 3회 이상 강조 표시
- 토큰 갱신 실패 / 시세 모드 종합 푸시 (백엔드 변경 필요 — 현재 별도 토픽 없음)
