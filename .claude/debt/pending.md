# 이해부채 원장 (Understanding Debt Ledger)

형식: `- [ ] <모르는 것> — @<branch>/<HEAD> — added <YYYY-MM-DD>`
정산: 새 탭/클린 세션에서 `/settle` → 완료 시 `- [x] ... — settled <날짜>` 로 아래로 이동.

## 미정산
- [ ] (예시) diffusion ResNet 블록이 gradient flow를 왜 쉽게 만드는가 — @main/abc1234 — added 2026-06-08
- [ ] github actions 배포 실행 중 deploy on VM 단계에서 일어나는 일 — @main/b0fdf2b — added 2026-06-08
- [ ] SignalState 전이의 히스테리시스(진입/해제 임계 분리)와 돌파 재인정(전고 갱신 시에만)이 왜 노이즈를 막는가 — @main/00fe1ce — added 2026-06-21
- [ ] 차트 N거래일 분봉을 base_dt 연속호출로 채우는 루프 — 왜 가장 이른 날을 base_dt로 주면 하루씩 채워지고 "필요일수+1"에서 멈추는가 — @main/7f0e0a2 — added 2026-06-21
- [ ] d3-hierarchy 트리맵(ThemeTreemap) — squarified 레이아웃 + paddingTop으로 그룹(테마) 헤더 밴드 확보하는 2단계 배치가 어떻게 동작하는가 — @main/HEAD — added 2026-06-23
- [ ] InvestorNetBuyState 데드밴드 레벨 전이 — 승급은 n×step, 강등은 n×step−buffer에서 일어나는 비대칭 히스테리시스가 경계 떨림 도배는 막으면서 회복(2조→1조)은 잡아내는 원리 — @main/15c6177 — added 2026-06-25
- [ ] 지수 1분봉 합성·캔들 4연속 — ka20001 10초 틱(inds_cur_prc_tm)을 분 단위 묶어 시가=첫틱/종가=끝틱으로 봉 만들고, (시장별 방향+마지막 발화 배수)로 디바운스해 5·10·15연속마다 한 번씩만 울리고(표시도 배수로 고정) 방향 바뀌면 리셋, 재시작 시 그날 DB 최신 캔들시그널로 상태 복원해 중복 발화 방지 — @main/722bfe9 — added 2026-06-25
- [ ] 지수 분봉 당일 누적 — ka20001 한 페이지가 ~4분뿐이라, 30초 폴러가 받는 겹치는 4분치를 IndexMinuteCandleStore에 분 단위 병합해 하루를 채우고 차트는 추가호출 0으로 저장소만 읽는 구조(백필은 키움 요청한도 429 유발로 제거, 라이브 누적만) — @main/HEAD — added 2026-06-26
- [ ] 순매수 단계 멱등 가드 — 메모리 state(InvestorNetBuyState)는 재시작 시 복원 안 돼 같은 단계가 재발화. 캔들처럼 DB 복원하는 대신 발화 직전 existsBy(date·시장·투자자·방향·단계)로 그날 1회만 허용. 이게 데드밴드 강등 발화(다른 단계라 통과)는 보존하면서 "같은 단계 재도달(3조→2.8조→3조)"만 차단하는 이유 — @main/HEAD — added 2026-06-28
- [ ] 순매수 흐름 전환(InvestorFlowState) — 진행 방향 정점(부호 포함 누적)을 들고, 정점 대비 절대 0.2조(REVERSAL_EOK, 시장 무관) 이상 반대로 되돌리면 전환 1회 발화 후 반대 방향 정점을 새로 추적(재전환). dir=±1로 부호 보정해 순매수/순매도 대칭 처리. 단계(NET_BUY_LEVEL)와 독립 state라 같은 폴에서 둘 다 나올 수 있는 구조 — @main/HEAD — added 2026-06-28
- [ ] 해외주식 ETF 필터 — 거래대금순위 API에 ETF 구분 필드가 없어 ename(영문명) 발행사 브랜드 키워드(SPDR·ISHARES·INVESCO·DIREXION 등)+" ETF/ETN"로 걸러냄. ename 48자 잘림으로 "ETF" 단어가 사라져도 발행사명으로 커버되는 이유와 이 휴리스틱의 한계(신규 발행사 누락) — @main/HEAD — added 2026-06-28
- [ ] 해외 실시간 로그 — 분봉이 종목당 12호출(KEYB 페이징)이라 후보 폴링 불가한 문제를, 폴러가 매 폴 최신 120건 1호출만 받아 OverseasMinuteCandleStore에 누적 병합하는 방식으로 해결(국내 IndexMinuteCandleStore와 동형). 돌파선은 누적분 최고가. 미국장이 한국 날짜 둘에 걸쳐 sessionDate(낮12시 전=전날)로 상태 관리. SignalState 히스테리시스는 국내 복제(Double) — @main/HEAD — added 2026-06-30
- [ ] 마감 스냅샷(MarketCloseSnapshot) — 타임라인을 장중 단계/전환 이벤트 대신 15:40 cron 1회 캡처(시장당 1행, existsBy로 멱등)로 전환. 폴러는 15:30에 멈추지만 sectorNetBuy 캐시 TTL 30초라 15:40엔 캐시 만료→신선한 마감값을 받는다는 타이밍 의존. 장중 이벤트(NET_BUY_LEVEL/NET_FLOW_TURN)는 SignalLogPage·LLM프롬프트용으로 그대로 유지하고 타임라인만 전용 테이블/엔드포인트로 분리 — @main/HEAD — added 2026-06-30
- [ ] 해외 돌파 현황 — 국내 레이더는 후보별 분봉을 캐시로 재계산하지만, 해외는 분봉 호출이 비싸 OverseasSignalEventPoller가 채워둔 OverseasMinuteCandleStore(누적 분봉)를 읽기만 해 전고점·갭을 구함(추가 API 0). 그래서 미국장 폴러가 도는 동안만 채워지고, 후보 풀이 폴러(min -7.0)⊇레이더(사용자 임계)라 레이더 종목은 보통 스토어에 있음. 돌파선 기준 기간이 국내(최근 3거래일 최고가)와 달리 세션 누적 최고가 — @main/HEAD — added 2026-06-30
