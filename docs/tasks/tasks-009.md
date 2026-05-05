# tasks-009 — Phase 6: 실적 + 운영 인지 채널 마무리

Goal: 실적 화면을 진짜 일별 실적 집계 데이터로 채우고, 시작 시 미마감 사이클 자동 처리 + 토큰/시세 모드 운영 인지 채널을 완성한다.

> 프론트 화면 골격은 [tasks-007](tasks-007.md), 실 API/STOMP wire는 [tasks-008](tasks-008.md)에서 완료. Phase 6은 백엔드 집계와 운영 인지의 마지막 한 겹을 채움.

---

## 백엔드 — 일별 실적 집계

- [ ] `report.application.DailyReportResult` — DTO (commandId, stockCode, stockName, closeReason, createdAt, closedAt, **avgBuyPrice, avgSellPrice, totalFee, totalTax, grossProfit, netProfit, profitRate**)
- [ ] `report.infrastructure.repository.DailyReportQuery` — 사이클별 집계 SQL (orders+executions JOIN, 매수/매도 평단·수수료·세금 합산)
- [ ] `report.application.ReportService.findDaily(date)` — TradingCycleRepository.findByCreatedAtBetween 결과를 DailyReportQuery로 집계 변환
- [ ] `report.presentation.ReportController` — `GET /api/reports/daily?date=YYYY-MM-DD` (default = 오늘)
- [ ] 기존 `TradingQueryService.findToday()`는 그대로 두되 ReportService를 재사용 (또는 deprecate 후 ReportService.findDaily(today) 호출)

## 백엔드 — 운영 인지 채널 보강

- [ ] `UnclosedCycleStartupHook` 통합 테스트 보강 — 시작 시 활성 명령(INITIATED/BUYING/HOLDING/LIQUIDATING)을 정확히 UNCLOSED로 마감 + `BALANCE_INVALIDATED` 발행 검증
- [ ] `KisAuthService` 토큰 상태 노출 — 토큰 갱신 실패 시 `SystemService.getStatus().tokenStatus`를 "EXPIRED" 또는 "FAIL"로 반환 (현재 하드코딩 "OK")
- [ ] (선택) 시세 모드 변경 시 `MarketModeChanged` 이벤트는 이미 발행 중 — `/topic/market`로 자동 푸시되므로 추가 작업 없음. 토큰 상태 푸시도 주기적 systemStatus refetch (30s)로 충분 → 별도 토픽 미도입.

## 백엔드 — 매도 재시도 명시

- [ ] `TradingDetailResult.activeSell` 필드 채우기 — orders[]에서 최신 미체결 SELL 주문의 `(signalType=trigger, retryCount, lastError)` 추출. 또는 필드 제거하고 프론트가 orders[]에서 직접 계산.

## 프론트 — 실적 화면 활성화

- [ ] `useDailyReport(date)` 쿼리 훅 추가 — `GET /api/reports/daily?date=` 호출
- [ ] ReportPage 데이터 소스 교체 — `useTodayClosed()` (오늘 한정) → `useDailyReport(selectedDate)`
- [ ] 날짜 선택기 활성화 — 좌우 화살표/캘린더 동작, 이전 영업일 이동 가능
- [ ] 거래 내역 테이블 — `매수→매도가` 컬럼 활성 (avgBuyPrice → avgSellPrice 표시)
- [ ] 요약 카드 "순수익" — totalFee/totalTax 분리 라인 표시 ("수수료 -1,200 / 세금 -3,100")

## 프론트 — 매도 재시도 강조

- [ ] DetailPanel "주문 이력" 테이블 — 활성 SELL 주문에 retryCount ≥ 3 시 행 강조 + lastError 인라인 노출
- [ ] DetailPanel 상단 — activeSell이 있으면 "🔥 매도 재시도 N회 — {lastError}" 알림 박스 추가

## 후속 분리 — KIS 종목 마스터 캐싱

- [ ] (선택, 별도 이슈) KRX 종목 마스터 (`kospi_code.mst`, `kosdaq_code.mst`) 다운로드 + 메모리/DB 캐시 → 종목명 부분 일치 검색 지원

## Verification

- [ ] `./gradlew test` 통과 — DailyReportQuery 집계 결과 정확도 (수수료+세금+순수익 합산), UnclosedCycleStartupHook 시나리오
- [ ] `npm run build` 0 에러
- [ ] 수동 검증: 백엔드 + 프론트 동시 기동 → 실적 화면에서 어제 일자 선택 → 거래 내역 + 합계 정상 표시
- [ ] 수동 검증: 활성 명령 있는 상태에서 백엔드 재시작 → 시작 시 UNCLOSED 자동 마감 + 모니터링 화면 "오늘 종료" 섹션에 등장 + 종 배지 +1
- [ ] 수동 검증: 매도 재시도 3회 이상 시 DetailPanel에 강조 표시

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
