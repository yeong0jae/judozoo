-- 이슈 메모·관심 테마 기능 제거(017 공개 전환)에 따른 스키마 정리.
-- 앱은 DDL을 만들지 않으므로 각 환경에 수동 적용한다(로컬·운영 각각).
--
-- 017에서는 "코드가 안 쓰면 무해하고 DROP은 되돌릴 수 없다"는 이유로 테이블을 남겼다.
-- 그 뒤 018로 Cloud SQL PITR(7일)이 생겨 되돌릴 방법이 있고, 코드 참조가 0인 것도
-- 확인했으므로 이제 지운다. 실수했다면 복제본을 세워서 퍼온다 —
--   gcloud sql instances clone judozoo-db-prod <임시> --point-in-time=<RFC3339>
-- 운영 인스턴스를 되감으면 그 이후의 정상 데이터까지 날아간다.

-- 일자별 이슈 메모. 사용자별 데이터인데 user_id가 없어, 공개 전환 시
-- 누구나 남의 메모를 고칠 수 있는 구조였다. 그래서 기능째 제거했다.
DROP TABLE IF EXISTS daily_issue;

-- 관심 테마. 자식(watch_theme_stock)을 먼저 지워야 FK가 걸리지 않는다.
DROP TABLE IF EXISTS watch_theme_stock;
DROP TABLE IF EXISTS watch_theme;

-- theme_snapshot 계열은 건드리지 않는다 — 15:40·20:00 캡처 폴러가 계속 쓴다.
