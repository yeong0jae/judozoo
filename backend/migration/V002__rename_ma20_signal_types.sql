-- 반등·꺾임 기준을 5분봉 20이평 → 1분봉 60이평으로 바꾸면서 타입 이름에서 주기를 뺀다.
-- 과거 행은 실제로 5분봉 20이평에서 발생했으므로, 주기를 담지 않는 중립 이름으로 옮긴다.
-- 앱은 DDL을 만들지 않으므로 각 환경에 수동 적용한다(로컬·운영 각각).
-- 코드 배포 후에 적용할 것 — 옛 코드는 새 이름을 모른다.

UPDATE signal_event SET event_type = 'MA_REBOUND'   WHERE event_type = 'MA20_REBOUND';
UPDATE signal_event SET event_type = 'MA_BREAKDOWN' WHERE event_type = 'MA20_BREAKDOWN';

UPDATE market_signal_event SET kind = 'MA_REBOUND'   WHERE kind = 'MA20_REBOUND';
UPDATE market_signal_event SET kind = 'MA_BREAKDOWN' WHERE kind = 'MA20_BREAKDOWN';
