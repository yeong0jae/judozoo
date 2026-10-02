-- 종베 체크 여섯 번째 "애프터 버팀"(20:00 종가 ≥ 15:30 정규장 종가) — 판 스냅샷에 정규장 종가를 남긴다.
-- 테이블은 앱이 기동 때 만들었지만(checkfirst) 열을 더하지는 않는다 — 각 환경에 수동 적용한다.
--
-- **이 파일을 먼저 적용하고 배포한다.** 반대로 하면 모델이 없는 열(regular_close)을 읽어
-- 20:00 체결과 복기 조회가 실패한다. 이미 찍힌 판은 None으로 남는다(그 판의 checks에는 after_hold가 없다).
ALTER TABLE bet_round_stock ADD COLUMN regular_close INT NULL;
