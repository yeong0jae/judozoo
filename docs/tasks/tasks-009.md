# tasks-009 — Phase 6: 실적 + 운영 인지 채널 마무리

Goal: 실적 화면을 진짜 일별 실적 집계 데이터로 채우고, 시작 시 미마감 사이클 자동 처리 + 토큰/시세 모드 운영 인지 채널을 완성한다.

> 프론트 화면 골격은 [tasks-007](tasks-007.md), 실 API/STOMP wire는 [tasks-008](tasks-008.md)에서 완료. Phase 6은 백엔드 집계와 운영 인지의 마지막 한 겹을 채움.

---

## 백엔드 — 일별 실적 집계

- [x] `report.application.DailyReportResult` — DTO (cycleId, stockCode, stockName, closeReason, createdAt, closedAt, **avgBuyPrice, avgSellPrice, totalFee, totalTax, grossProfit, netProfit, profitRate**)
- [x] `ReportService.aggregate()` — 사이클별 orders+executions 합산 (Kotlin 인메모리 집계 — v1 거래량 기준 충분, 별도 SQL 불필요)
- [x] `report.application.ReportService.findDaily(date)` — TradingCycleRepository.findByCreatedAtBetween 결과를 집계 변환
- [x] `report.presentation.ReportController` — `GET /api/reports/daily?date=YYYY-MM-DD` (default = 오늘)
- [x] 기존 `TradingQueryService.findToday()`는 그대로 유지 — 모니터링 화면의 "오늘 종료" 미니 섹션이 단순 DTO를 사용 (deprecate는 추후 결정)

## 백엔드 — 운영 인지 채널 보강

- [x] `UnclosedCycleStartupHook` 통합 테스트 보강 — 시작 시 활성 명령(INITIATED/BUYING/HOLDING/LIQUIDATING)을 정확히 UNCLOSED로 마감 + lifecycle CLOSED + `BALANCE_INVALIDATED` STOMP 발송 검증 (4개 시나리오)
- [ ] ~~`KisAuthService` 토큰 상태 노출~~ — token refresh 자체가 아직 미구현 (KisAccessTokenProvider는 lazy 1회 발급). refresh 메커니즘 도입 후 별도 작업으로 분리.
- [x] (선택) 시세 모드 변경 시 `MarketModeChanged` 이벤트는 이미 발행 중 — `/topic/market`로 자동 푸시. 토큰 상태 푸시는 주기적 systemStatus refetch(30s)로 충분 → 별도 토픽 미도입.

## 백엔드 — 매도 재시도 명시

- [x] `TradingDetailResult.activeSell` — 프론트가 orders[]에서 직접 계산하는 방식 채택 (백엔드 필드는 항상 null 유지, 향후 필요 시 채움)

## 프론트 — 실적 화면 활성화

- [x] `useDailyReport(date)` 쿼리 훅 추가 — `GET /api/reports/daily?date=` 호출
- [x] ReportPage 데이터 소스 교체 — `useTodayClosed()` → `useDailyReport(selectedDate)`
- [x] 날짜 선택기 활성화 — 좌우 화살표 / `<input type="date">` / "오늘" 버튼
- [x] 거래 내역 테이블 — `매수→매도가` 컬럼 활성 (avgBuyPrice → avgSellPrice 표시)
- [x] 요약 카드 "순수익" — totalFee/totalTax 분리 라인 표시 ("↳ 수수료 −N / ↳ 세금 −N")

## 프론트 — 매도 재시도 강조

- [x] DetailPanel "주문 이력" 테이블 — 활성 SELL 주문에 retryCount ≥ 3 시 행 배경 강조 + lastError tooltip
- [x] DetailPanel 상단 `ActiveSellAlert` — 활성 SELL retryCount > 0 시 알림 박스 (≥3은 빨강 강조)

## 후속 분리 — KIS 종목 마스터 캐싱

- [ ] (선택, 별도 이슈) KRX 종목 마스터 (`kospi_code.mst`, `kosdaq_code.mst`) 다운로드 + 메모리/DB 캐시 → 종목명 부분 일치 검색 지원

## Verification

- [x] `./gradlew test` 통과 — ReportService 집계 정확도(수수료+세금+순수익) + UnclosedCycleStartupHook 4개 시나리오 (lifecycle CLOSED/BALANCE_INVALIDATED 발송 포함)
- [x] `npm run build` 0 에러
- [x] 수동 검증: 백엔드 + 프론트 동시 기동 → 실적 화면에서 어제 일자 선택 → 거래 내역 + 합계 정상 표시
- [x] 수동 검증: 활성 명령 있는 상태에서 백엔드 재시작 → 시작 시 UNCLOSED 자동 마감 + 모니터링 "오늘 종료" 섹션 + 종 배지 +1
- [x] 수동 검증: 매도 재시도 3회 이상 시 DetailPanel에 강조 표시

---

## Definition of Done

- 일별 실적 합계가 수수료/세금까지 정확히 합산됨
- 시작 hook이 활성 명령을 UNCLOSED로 자동 마감
- 토큰 갱신 실패 시 시스템 배지가 30s 내 "토큰 오류"로 갱신
- 매도 재시도 3회 이상 강조가 데이터 흐름에 따라 자동 표시

## 후속 / Phase 7 (tasks-010)

- 실거래 1주 단위 sanity check
- bootJar + systemd/launchd, MySQL 백업 cron, 로그 회전
- 운영 체크리스트 (매일/매주)
