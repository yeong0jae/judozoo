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
- [ ] 지수 1분봉 합성·캔들 4연속 — ka20001 10초 틱(inds_cur_prc_tm)을 분 단위 묶어 시가=첫틱/종가=끝틱으로 봉 만들고, "연속 구간 첫 봉 시각"으로 1회만 발화시키는 디바운스가 왜 5·6연속 재발화를 막고 끊긴 뒤 새 4연속은 잡는가 — @main/722bfe9 — added 2026-06-25

## 정산 완료
