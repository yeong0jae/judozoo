-- 종베 체크 다섯 번째를 "신고가·박스 돌파"에서 "최근 고점과의 거리"로 바꾼다(종목 상세의 주도주 조건과 같은 식).
-- 테이블은 앱이 기동 때 만들었지만(checkfirst) 열을 바꾸지는 않는다 — 각 환경에 수동 적용한다.
--
-- **이 파일을 먼저 적용하고 배포한다.** 반대로 하면 모델이 없는 열(recent_high_gap)을 읽어
-- 20:00 체결과 복기 조회가 실패한다. 첫 판이 찍히기 전(2026-10-02 20:00 전)이라 버릴 값이 없다.
ALTER TABLE bet_round_stock DROP COLUMN level, ADD COLUMN recent_high_gap DOUBLE NULL;
