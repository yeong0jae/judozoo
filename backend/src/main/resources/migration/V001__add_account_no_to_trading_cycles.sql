-- TradingCycle.accountNo 컬럼 추가 + 기존 row를 vts 계좌(REDACTED_ACCOUNT)로 backfill
-- ddl-auto=update가 NOT NULL 컬럼을 직접 추가할 때 기존 row의 NULL로 실패하므로 3단계로 분리

ALTER TABLE trading_cycles
    ADD COLUMN account_no VARCHAR(16);

UPDATE trading_cycles
SET account_no = 'REDACTED_ACCOUNT';

ALTER TABLE trading_cycles
    MODIFY COLUMN account_no VARCHAR(16) NOT NULL;
