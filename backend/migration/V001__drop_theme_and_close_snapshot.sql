-- 테마 캘린더 · 일별 마감 기능 삭제에 따른 스키마 정리.
-- 앱은 DDL을 만들지 않으므로 각 환경에 수동 적용한다(로컬·운영 각각).
-- 되돌릴 수 없다 — 운영은 적용 전 infra/deploy/backup_db.sh 백업을 확인할 것.

-- 테마 캘린더: 과거 일자 API가 없어 매일 캡처해 누적한 데이터라 복구 불가.
DROP TABLE IF EXISTS theme_daily_stock;
DROP TABLE IF EXISTS theme_daily;

-- 일별 마감 스냅샷(코스피·코스닥 15:40, 나스닥종합 06:10).
DROP TABLE IF EXISTS market_close_snapshot;
DROP TABLE IF EXISTS overseas_index_close_snapshot;

-- 시그널 이벤트의 테마 라벨(키움 테마 API 제거로 더 이상 채워지지 않음).
ALTER TABLE signal_event DROP COLUMN theme;
