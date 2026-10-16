-- 기준선 — Alembic이 마지막으로 만든 스키마 그대로다(2026-10-16, 028 4단계). 운영 DB는 이미 이 상태라
-- `baseline-on-migrate`로 V1을 건너뛰고 기록만 남긴다. 빈 DB(통합 테스트·새 환경)에서만 실제로 돈다.
-- **고치지 않는다** — 스키마를 바꾸려면 V2부터 새 파일을 더한다.
CREATE TABLE `app_user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `google_sub` varchar(64) NOT NULL,
  `email` varchar(255) NOT NULL,
  `created_at` datetime NOT NULL,
  `last_login_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `google_sub` (`google_sub`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `bet_player` (
  `user_id` bigint NOT NULL AUTO_INCREMENT,
  `nickname` varchar(10) NOT NULL,
  `created_at` datetime NOT NULL,
  `renamed_at` datetime DEFAULT NULL,
  PRIMARY KEY (`user_id`),
  UNIQUE KEY `nickname` (`nickname`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `bet_round` (
  `trading_day` date NOT NULL,
  `snapped_at` datetime NOT NULL,
  `market` json NOT NULL,
  `players` int NOT NULL,
  `pot_man` bigint NOT NULL,
  `settled_at` datetime DEFAULT NULL,
  PRIMARY KEY (`trading_day`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `bet_round_stock` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `trading_day` date NOT NULL,
  `stock_code` varchar(6) NOT NULL,
  `stock_name` varchar(100) NOT NULL,
  `market` varchar(6) NOT NULL,
  `lead` tinyint(1) NOT NULL,
  `nxt` tinyint(1) NOT NULL,
  `close_price` int NOT NULL,
  `high_price` int NOT NULL,
  `low_price` int NOT NULL,
  `change_rate` float NOT NULL,
  `foreign_net` bigint NOT NULL,
  `institution_net` bigint NOT NULL,
  `foreign_5d` bigint NOT NULL,
  `institution_5d` bigint NOT NULL,
  `recent_high_gap` float DEFAULT NULL,
  `regular_close` int DEFAULT NULL,
  `checks` json NOT NULL,
  `grade` varchar(1) NOT NULL,
  `crowd` int NOT NULL,
  `pot_man` bigint NOT NULL,
  `sell_price` decimal(12,1) DEFAULT NULL,
  `sell_window_start` datetime DEFAULT NULL,
  `rate` float DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_bet_round_stock` (`trading_day`,`stock_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `broker_token` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `provider` varchar(16) NOT NULL,
  `access_token` text NOT NULL,
  `expires_at` datetime NOT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `provider` (`provider`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `closing_bet` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `trading_day` date NOT NULL,
  `stock_code` varchar(6) NOT NULL,
  `stock_name` varchar(100) NOT NULL,
  `amount_man` int NOT NULL,
  `placed_at` datetime NOT NULL,
  `status` varchar(8) NOT NULL,
  `buy_price` int DEFAULT NULL,
  `shares` int DEFAULT NULL,
  `sell_price` decimal(12,1) DEFAULT NULL,
  `pnl` bigint DEFAULT NULL,
  `rate` float DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_closing_bet_user_day` (`user_id`,`trading_day`),
  KEY `idx_closing_bet_day` (`trading_day`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `closing_bet_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `trading_day` date NOT NULL,
  `user_id` bigint NOT NULL,
  `stock_name` varchar(100) NOT NULL,
  `amount_man` int NOT NULL,
  `at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_closing_bet_event_day` (`trading_day`,`at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `feedback` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint DEFAULT NULL,
  `content` text NOT NULL,
  `created_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_feedback_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `futures_investor_snapshot` (
  `id` int NOT NULL AUTO_INCREMENT,
  `market` enum('KOSPI','KOSDAQ') NOT NULL,
  `trade_date` date NOT NULL,
  `captured_at` datetime NOT NULL,
  `foreign_qty` bigint NOT NULL,
  `institution_qty` bigint NOT NULL,
  `individual_qty` bigint NOT NULL,
  `securities_qty` bigint NOT NULL,
  `insurance_qty` bigint NOT NULL,
  `merchant_bank_qty` bigint NOT NULL,
  `trust_qty` bigint NOT NULL,
  `private_equity_qty` bigint NOT NULL,
  `fund_qty` bigint NOT NULL,
  `bank_qty` bigint NOT NULL,
  `other_org_qty` bigint NOT NULL,
  `other_corp_qty` bigint NOT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_futures_investor_snapshot_lookup` (`market`,`trade_date`,`captured_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `futures_minute_day` (
  `market` enum('KOSPI','KOSDAQ') NOT NULL,
  `contract_code` varchar(20) NOT NULL,
  `night` tinyint(1) NOT NULL,
  `trade_date` date NOT NULL,
  `candles` json NOT NULL,
  PRIMARY KEY (`market`,`contract_code`,`night`,`trade_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `index_minute_candle` (
  `id` int NOT NULL AUTO_INCREMENT,
  `market` enum('KOSPI','KOSDAQ') NOT NULL,
  `trade_date` date NOT NULL,
  `minute_at` datetime NOT NULL,
  `open_price` float NOT NULL,
  `high_price` float NOT NULL,
  `low_price` float NOT NULL,
  `close_price` float NOT NULL,
  `volume` bigint NOT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_index_minute_candle` (`market`,`minute_at`),
  KEY `idx_index_minute_candle_date` (`market`,`trade_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `index_minute_day` (
  `market` enum('KOSPI','KOSDAQ') NOT NULL,
  `trade_date` date NOT NULL,
  `candles` json NOT NULL,
  PRIMARY KEY (`market`,`trade_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `leader_day` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `region` enum('KR','US') NOT NULL,
  `trade_date` date NOT NULL,
  `taken_at` datetime NOT NULL,
  `closed` tinyint(1) NOT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_leader_day` (`region`,`trade_date`),
  KEY `idx_leader_day_date` (`trade_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `leader_day_stock` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `leader_day_id` bigint NOT NULL,
  `rank` smallint NOT NULL,
  `exchange` varchar(3) DEFAULT NULL,
  `code` varchar(16) NOT NULL,
  `name` varchar(100) NOT NULL,
  `price` decimal(18,4) NOT NULL,
  `change_rate` float NOT NULL,
  `trading_value` decimal(20,2) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_leader_day_stock` (`leader_day_id`,`rank`),
  KEY `idx_leader_day_stock_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `leader_tick` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `region` enum('KR','US') NOT NULL,
  `trade_date` date NOT NULL,
  `at` datetime NOT NULL,
  `created_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_leader_tick` (`region`,`at`),
  KEY `idx_leader_tick_day` (`region`,`trade_date`,`at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `leader_tick_stock` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `leader_tick_id` bigint NOT NULL,
  `rank` smallint NOT NULL,
  `exchange` varchar(3) DEFAULT NULL,
  `code` varchar(16) NOT NULL,
  `name` varchar(100) NOT NULL,
  `price` decimal(18,4) NOT NULL,
  `change_rate` float NOT NULL,
  `trading_value` decimal(20,2) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_leader_tick_stock` (`leader_tick_id`,`rank`),
  KEY `idx_leader_tick_stock_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `market_flow_state` (
  `id` int NOT NULL AUTO_INCREMENT,
  `market` enum('KOSPI','KOSDAQ') NOT NULL,
  `investor` enum('FOREIGN','INSTITUTION','INDIVIDUAL') NOT NULL,
  `side` enum('BUY','SELL') NOT NULL,
  `extreme_eok` bigint NOT NULL,
  `trade_date` date NOT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_market_flow_state` (`market`,`investor`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `market_investor_snapshot` (
  `id` int NOT NULL AUTO_INCREMENT,
  `market` enum('KOSPI','KOSDAQ') NOT NULL,
  `trade_date` date NOT NULL,
  `captured_at` datetime NOT NULL,
  `foreign_eok` bigint NOT NULL,
  `institution_eok` bigint NOT NULL,
  `individual_eok` bigint NOT NULL,
  `other_corp_eok` bigint NOT NULL,
  `financial_investment_eok` bigint NOT NULL,
  `trust_eok` bigint NOT NULL,
  `pension_fund_eok` bigint NOT NULL,
  `private_equity_eok` bigint NOT NULL,
  `insurance_eok` bigint NOT NULL,
  `bank_eok` bigint NOT NULL,
  `other_finance_eok` bigint NOT NULL,
  `index_value` float NOT NULL,
  `change_rate` float NOT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_market_investor_snapshot_lookup` (`market`,`trade_date`,`captured_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `market_signal_event` (
  `id` int NOT NULL AUTO_INCREMENT,
  `occurred_at` datetime NOT NULL,
  `trade_date` date NOT NULL,
  `kind` varchar(32) NOT NULL,
  `market` enum('KOSPI','KOSDAQ') NOT NULL,
  `side` enum('BUY','SELL') NOT NULL,
  `investor` enum('FOREIGN','INSTITUTION','INDIVIDUAL') DEFAULT NULL,
  `step_level` int DEFAULT NULL,
  `net_amount_eok` bigint DEFAULT NULL,
  `extreme_amount_eok` bigint DEFAULT NULL,
  `index_value` float DEFAULT NULL,
  `change_rate` float DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_market_signal_event_date` (`trade_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `overseas_minute_candle` (
  `id` int NOT NULL AUTO_INCREMENT,
  `exchange` varchar(8) NOT NULL,
  `symbol` varchar(16) NOT NULL,
  `trading_day` date NOT NULL,
  `minute_at` datetime NOT NULL,
  `open` float NOT NULL,
  `high` float NOT NULL,
  `low` float NOT NULL,
  `close` float NOT NULL,
  `volume` bigint NOT NULL,
  `trading_value` float NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_overseas_minute_candle` (`exchange`,`symbol`,`minute_at`),
  KEY `idx_overseas_minute_candle_day` (`exchange`,`symbol`,`trading_day`),
  KEY `idx_overseas_minute_candle_trading_day` (`trading_day`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `overseas_stocks` (
  `id` int NOT NULL AUTO_INCREMENT,
  `exchange` varchar(3) NOT NULL,
  `symbol` varchar(16) NOT NULL,
  `name` varchar(100) NOT NULL,
  `english_name` varchar(120) NOT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_overseas_stocks_symbol` (`exchange`,`symbol`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `signal_event` (
  `id` int NOT NULL AUTO_INCREMENT,
  `occurred_at` datetime NOT NULL,
  `trade_date` date NOT NULL,
  `stock_code` varchar(255) NOT NULL,
  `stock_name` varchar(255) NOT NULL,
  `event_type` varchar(32) NOT NULL,
  `current_price` bigint NOT NULL,
  `price_change_rate` float NOT NULL,
  `trading_value` bigint NOT NULL,
  `gap_rate` float DEFAULT NULL,
  `spike_ratio` float DEFAULT NULL,
  `minute_trading_value` bigint DEFAULT NULL,
  `spike_direction` enum('BUY','SELL','FLAT') DEFAULT NULL,
  `ma` bigint DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_signal_event_date` (`trade_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `stock_minute_candle` (
  `id` int NOT NULL AUTO_INCREMENT,
  `stock_code` varchar(12) NOT NULL,
  `trade_date` date NOT NULL,
  `minute_at` datetime NOT NULL,
  `open_price` bigint NOT NULL,
  `high_price` bigint NOT NULL,
  `low_price` bigint NOT NULL,
  `close_price` bigint NOT NULL,
  `volume` bigint NOT NULL,
  `trading_value` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stock_minute_candle` (`stock_code`,`minute_at`),
  KEY `idx_stock_minute_candle_day` (`stock_code`,`trade_date`),
  KEY `idx_stock_minute_candle_trade_date` (`trade_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `stock_reason` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `region` enum('KR','US') NOT NULL,
  `trading_day` date NOT NULL,
  `code` varchar(16) NOT NULL,
  `name` varchar(100) NOT NULL,
  `generated_at` datetime NOT NULL,
  `trigger` varchar(10) NOT NULL,
  `published` tinyint(1) NOT NULL,
  `explained` tinyint(1) NOT NULL,
  `keywords` json NOT NULL,
  `reason` varchar(200) NOT NULL,
  `evidence` json NOT NULL,
  `related` json NOT NULL,
  `model` varchar(40) NOT NULL,
  `error` text,
  `failure` varchar(16) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_stock_reason_day` (`region`,`trading_day`,`code`,`generated_at`),
  KEY `idx_stock_reason_generated` (`generated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `stocks` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `short_code` varchar(12) NOT NULL,
  `standard_code` varchar(12) NOT NULL,
  `name` varchar(50) NOT NULL,
  `market` enum('KOSPI','KOSDAQ') NOT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `short_code` (`short_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `wide_limit_day` (
  `id` int NOT NULL AUTO_INCREMENT,
  `trade_date` date NOT NULL,
  `stock_code` varchar(12) NOT NULL,
  `created_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wide_limit_day` (`trade_date`,`stock_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `yahoo_minute_day` (
  `symbol` varchar(20) NOT NULL,
  `trade_date` date NOT NULL,
  `candles` json NOT NULL,
  PRIMARY KEY (`symbol`,`trade_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


