-- 주도주 캘린더에 휴장일을 남긴다. 테이블은 앱이 기동 때 만들었지만(checkfirst) 열을 더하지는 않는다.
-- 앱은 ALTER를 하지 않으므로 각 환경에 수동 적용한다(로컬·운영 각각).
--
-- **이 파일을 먼저 적용하고 배포한다.** 반대로 하면 모델이 없는 열(closed)을 읽어
-- 캘린더 조회와 스냅샷이 전부 실패한다.
--
-- 휴장일은 "날 행 + 종목 없음 + closed"로 남는다. closed가 없으면 휴장과 "주도주 없음"이 갈리지 않는다.
ALTER TABLE leader_day ADD COLUMN closed BOOLEAN NOT NULL DEFAULT FALSE;
