-- 단일 매수 전환: 다회차 매수(회차/간격)·중도 익절 기능 제거에 따른 컬럼 정리.
-- ddl-auto=update는 컬럼을 드롭하지 않으므로 기존 DB(로컬·kis-real·kiwoom-real)에 수동 적용 필요.
ALTER TABLE trading_cycles
    DROP COLUMN buy_attempt,
    DROP COLUMN buy_interval_min,
    DROP COLUMN midway_profit_pct;
