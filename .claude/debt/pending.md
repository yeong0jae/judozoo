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
- [ ] 시그널 라벨링(MinuteCandles.labelFor) — occurredAt은 폴링 wall-clock인데 분봉 cntr_tm은 HTS보다 1분 이르다. +N분 봉을 "occurredAt+N분 이후 첫 봉"으로 잡는 상대계산이라 이 오프셋을 무시해도 되는 근거(절대 정합성 불필요), ka10080이 base_dt 며칠치를 함께 줘서 tradeDate 봉만 필터하는 이유 — @main/HEAD — added 2026-06-27
- [ ] 시그널 분석 전체 통계 — overallStats가 signalEvent/signalLabel 전건 findAll 후 메모리 집계. 표본 적은 동안만 유효하고 데이터 누적 시 집계 쿼리/캐시로 전환 필요한 임계 — @main/HEAD — added 2026-06-27

## 정산 완료
