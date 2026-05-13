# tasks-010 — Phase 7: 보유 주식 수동 매도

Goal: 시스템 사이클과 무관하게 KIS 계좌에 남아있는 보유 주식을 조회하고, 종목별로 보유 전량 시장가 매도를 수동 실행할 수 있는 "보유 주식" 탭을 추가한다. 예상치 못한 오류로 사이클이 종료됐지만 포지션이 KIS 계좌에 남은 케이스의 수동 정리 수단.

> 데이터 출처: KIS 잔고 조회(`inquire-balance`)의 `output1` 실 보유분 — 우리 사이클 기록이 아니라 증권사 계좌가 source of truth. `account` feature(`AccountService`/`AccountController`)에 엔드포인트 추가.

---

## 백엔드 — 보유 주식 조회

- [x] `KisBalanceResponse`에 `output1` 매핑 추가 — 보유 종목 배열: `pdno`(종목코드), `prdt_name`(종목명), `hldg_qty`(보유수량), `pchs_avg_pric`(매입평균가), `prpr`(현재가), `evlu_pfls_amt`(평가손익금액), `evlu_pfls_rt`(평가손익률). WireMock fixture `balance.json`을 `output1` 포함하도록 보강 (`KisRestClientTest` 영향 확인).
- [x] `account.application.AccountService.getHoldings()` — KIS 잔고 `output1` → `HoldingResult` 리스트 변환. 보유수량 0 행 제외. 각 행에 `hasActiveCycle: Boolean` 부여 (`OPEN` = ACTIVE + LIQUIDATING 상태 사이클 존재 여부 — LIQUIDATING 중 수동 매도도 충돌 위험이라 차단).
- [x] `account.presentation.AccountController` — `GET /api/account/holdings` → `List<HoldingResult>`

## 백엔드 — 보유 전량 시장가 매도

- [ ] `account.application.AccountService.liquidate(stockCode)` — KIS 잔고에서 해당 종목 보유수량 조회 → 0이면 거부(예외), 아니면 `KisRestClient.requestOrder(stockCode, SELL, qty)` 시장가 1회 발송 (재시도 없음 — 체결 확인은 사용자가 재조회). 응답을 `LiquidateResult`(odno 등)로 반환. 활성 사이클이 있는 종목이어도 API 레벨에선 막지 않음 — UI 가드가 1차 방어선, API는 운영자 강제 개입 여지 유지하되 호출 시 WARN 로그.
- [ ] `AccountController` — `POST /api/account/holdings/{stockCode}/sell` → `liquidate` 호출. KIS 거부 시 `GlobalExceptionHandler` 경유 적절한 HTTP 에러.

## 프론트 — 보유 주식 탭

- [ ] 헤더 네비게이션에 "보유 주식" 탭 추가 (매매 명령 / 모니터링 / 실적 / **보유 주식**) + 라우트 `/holdings`
- [ ] `types.ts` — `Holding` 타입 (stockCode, stockName, qty, avgBuyPrice, currentPrice, evalProfit, evalProfitRate, hasActiveCycle)
- [ ] `api/queries.ts` `useHoldings()` (`GET /api/account/holdings`) + `api/mutations.ts` `useLiquidateHolding()` (`POST /api/account/holdings/{code}/sell`) → 성공 시 holdings·accountBalance invalidate + 토스트
- [ ] `pages/HoldingsPage.tsx` — 보유 주식 리스트 (종목명/코드, 보유수량, 평균매입가, 현재가, 평가손익 `ProfitText` / 평가손익률 `formatPct`) + 행마다 "시장가 매도" 버튼. `hasActiveCycle` 행은 "매매 중" 배지(`StatusPill` 또는 전용 배지) + 매도 버튼 disabled. 빈 목록 시 안내 문구.
- [ ] "시장가 매도" 클릭 → 확인 다이얼로그("{종목명} {N}주를 시장가로 전량 매도합니다. 진행할까요?") → 확인 시 mutation 호출. (모니터링 취소 버튼과 동일한 2단계 안전망 패턴)

> `.js` 미러 파일(`pages/*.js` 등)은 빌드 산출물 잔재이며 엔트리(`main.tsx`)에서 미사용 — 이번 작업에서 손대지 않음.

## Verification

- [ ] `./gradlew test` — `AccountService.getHoldings`(output1 매핑 / 보유0 제외 / hasActiveCycle 판정) + `liquidate`(보유0 거부 / 정상 발송) + `AccountControllerTest`. `balance.json` fixture 변경에 따른 `KisRestClientTest` 그린.
- [ ] `npm run build` 0 에러
- [ ] 수동 검증: 백엔드+프론트 기동 → "보유 주식" 탭에 KIS 계좌 보유분 표시 → orphan 종목 "시장가 매도" → 확인 다이얼로그 → KIS 매도 주문 발송 로그 확인 → 재조회 시 수량 감소(또는 사라짐)
- [ ] 수동 검증: 활성 사이클 있는 종목은 "매매 중" 배지 + 매도 버튼 비활성

---

## Definition of Done

- "보유 주식" 탭에서 KIS 계좌의 실제 보유 주식이 보임
- orphan 보유분을 보유 전량 시장가 매도로 수동 청산 가능
- 활성 사이클 종목은 수동 매도 버튼이 비활성이라 사이클과 충돌하지 않음

## 후속

- Phase 8 (tasks-011, 보류) — 로컬 실행 시작
