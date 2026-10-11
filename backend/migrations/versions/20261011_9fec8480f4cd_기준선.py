"""기준선

Revision ID: 9fec8480f4cd
Revises: 
Create Date: 2026-10-11 18:04:17.400172

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '9fec8480f4cd'
down_revision: Union[str, Sequence[str], None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """앱이 쓰는 테이블 전부. **이미 있는 테이블은 건너뛴다.**

    운영 DB는 Alembic 도입 전부터 이 테이블들을 갖고 있다(Kotlin JPA, 기동 시 checkfirst 생성,
    옛 migration/V001~V010 수동 SQL). 그래서 처음 배포 때 이 파일은 빠진 테이블만 만들고
    `alembic_version`에 기록을 남긴다. 빈 DB(로컬, 테스트)에서는 전부 만든다.

    있는 테이블의 컬럼이 이 정의와 다른지는 보지 않는다. 도입 전 checkfirst와 같은 동작이다.
    """
    existing = set(sa.inspect(op.get_bind()).get_table_names())
    if 'app_user' not in existing:
        op.create_table('app_user',
        sa.Column('id', sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column('google_sub', sa.String(length=64), nullable=False),
        sa.Column('email', sa.String(length=255), nullable=False),
        sa.Column('created_at', sa.DateTime(), nullable=False),
        sa.Column('last_login_at', sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint('id'),
        sa.UniqueConstraint('google_sub')
        )
    if 'bet_player' not in existing:
        op.create_table('bet_player',
        sa.Column('user_id', sa.BigInteger(), nullable=False),
        sa.Column('nickname', sa.String(length=10), nullable=False),
        sa.Column('created_at', sa.DateTime(), nullable=False),
        sa.Column('renamed_at', sa.DateTime(), nullable=True),
        sa.PrimaryKeyConstraint('user_id'),
        sa.UniqueConstraint('nickname')
        )
    if 'bet_round' not in existing:
        op.create_table('bet_round',
        sa.Column('trading_day', sa.Date(), nullable=False),
        sa.Column('snapped_at', sa.DateTime(), nullable=False),
        sa.Column('market', sa.JSON(), nullable=False),
        sa.Column('players', sa.Integer(), nullable=False),
        sa.Column('pot_man', sa.BigInteger(), nullable=False),
        sa.Column('settled_at', sa.DateTime(), nullable=True),
        sa.PrimaryKeyConstraint('trading_day')
        )
    if 'bet_round_stock' not in existing:
        op.create_table('bet_round_stock',
        sa.Column('id', sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column('trading_day', sa.Date(), nullable=False),
        sa.Column('stock_code', sa.String(length=6), nullable=False),
        sa.Column('stock_name', sa.String(length=100), nullable=False),
        sa.Column('market', sa.String(length=6), nullable=False),
        sa.Column('lead', sa.Boolean(), nullable=False),
        sa.Column('nxt', sa.Boolean(), nullable=False),
        sa.Column('close_price', sa.Integer(), nullable=False),
        sa.Column('high_price', sa.Integer(), nullable=False),
        sa.Column('low_price', sa.Integer(), nullable=False),
        sa.Column('change_rate', sa.Float(), nullable=False),
        sa.Column('foreign_net', sa.BigInteger(), nullable=False),
        sa.Column('institution_net', sa.BigInteger(), nullable=False),
        sa.Column('foreign_5d', sa.BigInteger(), nullable=False),
        sa.Column('institution_5d', sa.BigInteger(), nullable=False),
        sa.Column('recent_high_gap', sa.Float(), nullable=True),
        sa.Column('regular_close', sa.Integer(), nullable=True),
        sa.Column('checks', sa.JSON(), nullable=False),
        sa.Column('grade', sa.String(length=1), nullable=False),
        sa.Column('crowd', sa.Integer(), nullable=False),
        sa.Column('pot_man', sa.BigInteger(), nullable=False),
        sa.Column('sell_price', sa.Numeric(precision=12, scale=1, asdecimal=False), nullable=True),
        sa.Column('sell_window_start', sa.DateTime(), nullable=True),
        sa.Column('rate', sa.Float(), nullable=True),
        sa.PrimaryKeyConstraint('id'),
        sa.UniqueConstraint('trading_day', 'stock_code', name='uq_bet_round_stock')
        )
    if 'broker_token' not in existing:
        op.create_table('broker_token',
        sa.Column('id', sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column('provider', sa.String(length=16), nullable=False),
        sa.Column('access_token', sa.Text(), nullable=False),
        sa.Column('expires_at', sa.DateTime(), nullable=False),
        sa.Column('created_at', sa.DateTime(), nullable=False),
        sa.Column('updated_at', sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint('id'),
        sa.UniqueConstraint('provider')
        )
    if 'closing_bet' not in existing:
        op.create_table('closing_bet',
        sa.Column('id', sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column('user_id', sa.BigInteger(), nullable=False),
        sa.Column('trading_day', sa.Date(), nullable=False),
        sa.Column('stock_code', sa.String(length=6), nullable=False),
        sa.Column('stock_name', sa.String(length=100), nullable=False),
        sa.Column('amount_man', sa.Integer(), nullable=False),
        sa.Column('placed_at', sa.DateTime(), nullable=False),
        sa.Column('status', sa.String(length=8), nullable=False),
        sa.Column('buy_price', sa.Integer(), nullable=True),
        sa.Column('shares', sa.Integer(), nullable=True),
        sa.Column('sell_price', sa.Numeric(precision=12, scale=1, asdecimal=False), nullable=True),
        sa.Column('pnl', sa.BigInteger(), nullable=True),
        sa.Column('rate', sa.Float(), nullable=True),
        sa.PrimaryKeyConstraint('id'),
        sa.UniqueConstraint('user_id', 'trading_day', name='uq_closing_bet_user_day')
        )
        op.create_index('idx_closing_bet_day', 'closing_bet', ['trading_day', 'status'], unique=False)
    if 'closing_bet_event' not in existing:
        op.create_table('closing_bet_event',
        sa.Column('id', sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column('trading_day', sa.Date(), nullable=False),
        sa.Column('user_id', sa.BigInteger(), nullable=False),
        sa.Column('stock_name', sa.String(length=100), nullable=False),
        sa.Column('amount_man', sa.Integer(), nullable=False),
        sa.Column('at', sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint('id')
        )
        op.create_index('idx_closing_bet_event_day', 'closing_bet_event', ['trading_day', 'at'], unique=False)
    if 'feedback' not in existing:
        op.create_table('feedback',
        sa.Column('id', sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column('user_id', sa.BigInteger(), nullable=True),
        sa.Column('content', sa.Text(), nullable=False),
        sa.Column('created_at', sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint('id')
        )
    if 'futures_investor_snapshot' not in existing:
        op.create_table('futures_investor_snapshot',
        sa.Column('id', sa.Integer(), autoincrement=True, nullable=False),
        sa.Column('market', sa.Enum('KOSPI', 'KOSDAQ', name='market'), nullable=False),
        sa.Column('trade_date', sa.Date(), nullable=False),
        sa.Column('captured_at', sa.DateTime(), nullable=False),
        sa.Column('foreign_qty', sa.BigInteger(), nullable=False),
        sa.Column('institution_qty', sa.BigInteger(), nullable=False),
        sa.Column('individual_qty', sa.BigInteger(), nullable=False),
        sa.Column('securities_qty', sa.BigInteger(), nullable=False),
        sa.Column('insurance_qty', sa.BigInteger(), nullable=False),
        sa.Column('merchant_bank_qty', sa.BigInteger(), nullable=False),
        sa.Column('trust_qty', sa.BigInteger(), nullable=False),
        sa.Column('private_equity_qty', sa.BigInteger(), nullable=False),
        sa.Column('fund_qty', sa.BigInteger(), nullable=False),
        sa.Column('bank_qty', sa.BigInteger(), nullable=False),
        sa.Column('other_org_qty', sa.BigInteger(), nullable=False),
        sa.Column('other_corp_qty', sa.BigInteger(), nullable=False),
        sa.Column('created_at', sa.DateTime(), nullable=False),
        sa.Column('updated_at', sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint('id')
        )
        op.create_index('idx_futures_investor_snapshot_lookup', 'futures_investor_snapshot', ['market', 'trade_date', 'captured_at'], unique=False)
    if 'futures_minute_day' not in existing:
        op.create_table('futures_minute_day',
        sa.Column('market', sa.Enum('KOSPI', 'KOSDAQ', name='market'), nullable=False),
        sa.Column('contract_code', sa.String(length=20), nullable=False),
        sa.Column('night', sa.Boolean(), nullable=False),
        sa.Column('trade_date', sa.Date(), nullable=False),
        sa.Column('candles', sa.JSON(), nullable=False),
        sa.PrimaryKeyConstraint('market', 'contract_code', 'night', 'trade_date')
        )
    if 'index_minute_candle' not in existing:
        op.create_table('index_minute_candle',
        sa.Column('id', sa.Integer(), autoincrement=True, nullable=False),
        sa.Column('market', sa.Enum('KOSPI', 'KOSDAQ', name='market'), nullable=False),
        sa.Column('trade_date', sa.Date(), nullable=False),
        sa.Column('minute_at', sa.DateTime(), nullable=False),
        sa.Column('open_price', sa.Float(), nullable=False),
        sa.Column('high_price', sa.Float(), nullable=False),
        sa.Column('low_price', sa.Float(), nullable=False),
        sa.Column('close_price', sa.Float(), nullable=False),
        sa.Column('volume', sa.BigInteger(), nullable=False),
        sa.Column('created_at', sa.DateTime(), nullable=False),
        sa.Column('updated_at', sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint('id'),
        sa.UniqueConstraint('market', 'minute_at', name='uk_index_minute_candle')
        )
        op.create_index('idx_index_minute_candle_date', 'index_minute_candle', ['market', 'trade_date'], unique=False)
    if 'index_minute_day' not in existing:
        op.create_table('index_minute_day',
        sa.Column('market', sa.Enum('KOSPI', 'KOSDAQ', name='market'), nullable=False),
        sa.Column('trade_date', sa.Date(), nullable=False),
        sa.Column('candles', sa.JSON(), nullable=False),
        sa.PrimaryKeyConstraint('market', 'trade_date')
        )
    if 'leader_day' not in existing:
        op.create_table('leader_day',
        sa.Column('id', sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column('region', sa.Enum('KR', 'US', name='region'), nullable=False),
        sa.Column('trade_date', sa.Date(), nullable=False),
        sa.Column('taken_at', sa.DateTime(), nullable=False),
        sa.Column('closed', sa.Boolean(), nullable=False),
        sa.Column('created_at', sa.DateTime(), nullable=False),
        sa.Column('updated_at', sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint('id'),
        sa.UniqueConstraint('region', 'trade_date', name='uk_leader_day')
        )
        op.create_index('idx_leader_day_date', 'leader_day', ['trade_date'], unique=False)
    if 'leader_day_stock' not in existing:
        op.create_table('leader_day_stock',
        sa.Column('id', sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column('leader_day_id', sa.BigInteger(), nullable=False),
        sa.Column('rank', sa.SmallInteger(), nullable=False),
        sa.Column('exchange', sa.String(length=3), nullable=True),
        sa.Column('code', sa.String(length=16), nullable=False),
        sa.Column('name', sa.String(length=100), nullable=False),
        sa.Column('price', sa.Numeric(precision=18, scale=4), nullable=False),
        sa.Column('change_rate', sa.Float(), nullable=False),
        sa.Column('trading_value', sa.Numeric(precision=20, scale=2), nullable=False),
        sa.PrimaryKeyConstraint('id'),
        sa.UniqueConstraint('leader_day_id', 'rank', name='uk_leader_day_stock')
        )
        op.create_index('idx_leader_day_stock_code', 'leader_day_stock', ['code'], unique=False)
    if 'leader_tick' not in existing:
        op.create_table('leader_tick',
        sa.Column('id', sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column('region', sa.Enum('KR', 'US', name='region'), nullable=False),
        sa.Column('trade_date', sa.Date(), nullable=False),
        sa.Column('at', sa.DateTime(), nullable=False),
        sa.Column('created_at', sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint('id'),
        sa.UniqueConstraint('region', 'at', name='uk_leader_tick')
        )
        op.create_index('idx_leader_tick_day', 'leader_tick', ['region', 'trade_date', 'at'], unique=False)
    if 'leader_tick_stock' not in existing:
        op.create_table('leader_tick_stock',
        sa.Column('id', sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column('leader_tick_id', sa.BigInteger(), nullable=False),
        sa.Column('rank', sa.SmallInteger(), nullable=False),
        sa.Column('exchange', sa.String(length=3), nullable=True),
        sa.Column('code', sa.String(length=16), nullable=False),
        sa.Column('name', sa.String(length=100), nullable=False),
        sa.Column('price', sa.Numeric(precision=18, scale=4), nullable=False),
        sa.Column('change_rate', sa.Float(), nullable=False),
        sa.Column('trading_value', sa.Numeric(precision=20, scale=2), nullable=False),
        sa.PrimaryKeyConstraint('id'),
        sa.UniqueConstraint('leader_tick_id', 'rank', name='uk_leader_tick_stock')
        )
        op.create_index('idx_leader_tick_stock_code', 'leader_tick_stock', ['code'], unique=False)
    if 'market_flow_state' not in existing:
        op.create_table('market_flow_state',
        sa.Column('id', sa.Integer(), autoincrement=True, nullable=False),
        sa.Column('market', sa.Enum('KOSPI', 'KOSDAQ', name='market'), nullable=False),
        sa.Column('investor', sa.Enum('FOREIGN', 'INSTITUTION', 'INDIVIDUAL', name='investortype'), nullable=False),
        sa.Column('side', sa.Enum('BUY', 'SELL', name='nettradeside'), nullable=False),
        sa.Column('extreme_eok', sa.BigInteger(), nullable=False),
        sa.Column('trade_date', sa.Date(), nullable=False),
        sa.Column('created_at', sa.DateTime(), nullable=False),
        sa.Column('updated_at', sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint('id'),
        sa.UniqueConstraint('market', 'investor', name='uk_market_flow_state')
        )
    if 'market_investor_snapshot' not in existing:
        op.create_table('market_investor_snapshot',
        sa.Column('id', sa.Integer(), autoincrement=True, nullable=False),
        sa.Column('market', sa.Enum('KOSPI', 'KOSDAQ', name='market'), nullable=False),
        sa.Column('trade_date', sa.Date(), nullable=False),
        sa.Column('captured_at', sa.DateTime(), nullable=False),
        sa.Column('foreign_eok', sa.BigInteger(), nullable=False),
        sa.Column('institution_eok', sa.BigInteger(), nullable=False),
        sa.Column('individual_eok', sa.BigInteger(), nullable=False),
        sa.Column('other_corp_eok', sa.BigInteger(), nullable=False),
        sa.Column('financial_investment_eok', sa.BigInteger(), nullable=False),
        sa.Column('trust_eok', sa.BigInteger(), nullable=False),
        sa.Column('pension_fund_eok', sa.BigInteger(), nullable=False),
        sa.Column('private_equity_eok', sa.BigInteger(), nullable=False),
        sa.Column('insurance_eok', sa.BigInteger(), nullable=False),
        sa.Column('bank_eok', sa.BigInteger(), nullable=False),
        sa.Column('other_finance_eok', sa.BigInteger(), nullable=False),
        sa.Column('index_value', sa.Float(), nullable=False),
        sa.Column('change_rate', sa.Float(), nullable=False),
        sa.Column('created_at', sa.DateTime(), nullable=False),
        sa.Column('updated_at', sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint('id')
        )
        op.create_index('idx_market_investor_snapshot_lookup', 'market_investor_snapshot', ['market', 'trade_date', 'captured_at'], unique=False)
    if 'market_signal_event' not in existing:
        op.create_table('market_signal_event',
        sa.Column('id', sa.Integer(), autoincrement=True, nullable=False),
        sa.Column('occurred_at', sa.DateTime(), nullable=False),
        sa.Column('trade_date', sa.Date(), nullable=False),
        sa.Column('kind', sa.String(length=32), nullable=False),
        sa.Column('market', sa.Enum('KOSPI', 'KOSDAQ', name='market'), nullable=False),
        sa.Column('side', sa.Enum('BUY', 'SELL', name='nettradeside'), nullable=False),
        sa.Column('investor', sa.Enum('FOREIGN', 'INSTITUTION', 'INDIVIDUAL', name='investortype'), nullable=True),
        sa.Column('step_level', sa.Integer(), nullable=True),
        sa.Column('net_amount_eok', sa.BigInteger(), nullable=True),
        sa.Column('extreme_amount_eok', sa.BigInteger(), nullable=True),
        sa.Column('index_value', sa.Float(), nullable=True),
        sa.Column('change_rate', sa.Float(), nullable=True),
        sa.Column('created_at', sa.DateTime(), nullable=False),
        sa.Column('updated_at', sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint('id')
        )
        op.create_index('idx_market_signal_event_date', 'market_signal_event', ['trade_date'], unique=False)
    if 'overseas_minute_candle' not in existing:
        op.create_table('overseas_minute_candle',
        sa.Column('id', sa.Integer(), autoincrement=True, nullable=False),
        sa.Column('exchange', sa.String(length=8), nullable=False),
        sa.Column('symbol', sa.String(length=16), nullable=False),
        sa.Column('trading_day', sa.Date(), nullable=False),
        sa.Column('minute_at', sa.DateTime(), nullable=False),
        sa.Column('open', sa.Float(), nullable=False),
        sa.Column('high', sa.Float(), nullable=False),
        sa.Column('low', sa.Float(), nullable=False),
        sa.Column('close', sa.Float(), nullable=False),
        sa.Column('volume', sa.BigInteger(), nullable=False),
        sa.Column('trading_value', sa.Float(), nullable=False),
        sa.PrimaryKeyConstraint('id'),
        sa.UniqueConstraint('exchange', 'symbol', 'minute_at', name='uk_overseas_minute_candle')
        )
        op.create_index('idx_overseas_minute_candle_day', 'overseas_minute_candle', ['exchange', 'symbol', 'trading_day'], unique=False)
        op.create_index('idx_overseas_minute_candle_trading_day', 'overseas_minute_candle', ['trading_day'], unique=False)
    if 'overseas_stocks' not in existing:
        op.create_table('overseas_stocks',
        sa.Column('id', sa.Integer(), autoincrement=True, nullable=False),
        sa.Column('exchange', sa.String(length=3), nullable=False),
        sa.Column('symbol', sa.String(length=16), nullable=False),
        sa.Column('name', sa.String(length=100), nullable=False),
        sa.Column('english_name', sa.String(length=120), nullable=False),
        sa.Column('created_at', sa.DateTime(), nullable=False),
        sa.Column('updated_at', sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint('id')
        )
        op.create_index('idx_overseas_stocks_symbol', 'overseas_stocks', ['exchange', 'symbol'], unique=False)
    if 'signal_event' not in existing:
        op.create_table('signal_event',
        sa.Column('id', sa.Integer(), autoincrement=True, nullable=False),
        sa.Column('occurred_at', sa.DateTime(), nullable=False),
        sa.Column('trade_date', sa.Date(), nullable=False),
        sa.Column('stock_code', sa.String(length=255), nullable=False),
        sa.Column('stock_name', sa.String(length=255), nullable=False),
        sa.Column('event_type', sa.String(length=32), nullable=False),
        sa.Column('current_price', sa.BigInteger(), nullable=False),
        sa.Column('price_change_rate', sa.Float(), nullable=False),
        sa.Column('trading_value', sa.BigInteger(), nullable=False),
        sa.Column('gap_rate', sa.Float(), nullable=True),
        sa.Column('spike_ratio', sa.Float(), nullable=True),
        sa.Column('minute_trading_value', sa.BigInteger(), nullable=True),
        sa.Column('spike_direction', sa.Enum('BUY', 'SELL', 'FLAT', name='spikedirection'), nullable=True),
        sa.Column('ma', sa.BigInteger(), nullable=True),
        sa.Column('created_at', sa.DateTime(), nullable=False),
        sa.Column('updated_at', sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint('id')
        )
        op.create_index('idx_signal_event_date', 'signal_event', ['trade_date'], unique=False)
    if 'stock_minute_candle' not in existing:
        op.create_table('stock_minute_candle',
        sa.Column('id', sa.Integer(), autoincrement=True, nullable=False),
        sa.Column('stock_code', sa.String(length=12), nullable=False),
        sa.Column('trade_date', sa.Date(), nullable=False),
        sa.Column('minute_at', sa.DateTime(), nullable=False),
        sa.Column('open_price', sa.BigInteger(), nullable=False),
        sa.Column('high_price', sa.BigInteger(), nullable=False),
        sa.Column('low_price', sa.BigInteger(), nullable=False),
        sa.Column('close_price', sa.BigInteger(), nullable=False),
        sa.Column('volume', sa.BigInteger(), nullable=False),
        sa.Column('trading_value', sa.BigInteger(), nullable=False),
        sa.PrimaryKeyConstraint('id'),
        sa.UniqueConstraint('stock_code', 'minute_at', name='uk_stock_minute_candle')
        )
        op.create_index('idx_stock_minute_candle_day', 'stock_minute_candle', ['stock_code', 'trade_date'], unique=False)
        op.create_index('idx_stock_minute_candle_trade_date', 'stock_minute_candle', ['trade_date'], unique=False)
    if 'stock_reason' not in existing:
        op.create_table('stock_reason',
        sa.Column('id', sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column('region', sa.Enum('KR', 'US', name='region'), nullable=False),
        sa.Column('trading_day', sa.Date(), nullable=False),
        sa.Column('code', sa.String(length=16), nullable=False),
        sa.Column('name', sa.String(length=100), nullable=False),
        sa.Column('generated_at', sa.DateTime(), nullable=False),
        sa.Column('trigger', sa.String(length=10), nullable=False),
        sa.Column('published', sa.Boolean(), nullable=False),
        sa.Column('explained', sa.Boolean(), nullable=False),
        sa.Column('keywords', sa.JSON(), nullable=False),
        sa.Column('reason', sa.String(length=200), nullable=False),
        sa.Column('evidence', sa.JSON(), nullable=False),
        sa.Column('related', sa.JSON(), nullable=False),
        sa.Column('model', sa.String(length=40), nullable=False),
        sa.Column('error', sa.Text(), nullable=True),
        sa.Column('failure', sa.String(length=16), nullable=True),
        sa.PrimaryKeyConstraint('id')
        )
        op.create_index('idx_stock_reason_day', 'stock_reason', ['region', 'trading_day', 'code', 'generated_at'], unique=False)
        op.create_index('idx_stock_reason_generated', 'stock_reason', ['generated_at'], unique=False)
    if 'stocks' not in existing:
        op.create_table('stocks',
        sa.Column('id', sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column('short_code', sa.String(length=12), nullable=False),
        sa.Column('standard_code', sa.String(length=12), nullable=False),
        sa.Column('name', sa.String(length=50), nullable=False),
        sa.Column('market', sa.Enum('KOSPI', 'KOSDAQ', name='market'), nullable=False),
        sa.Column('created_at', sa.DateTime(), nullable=False),
        sa.Column('updated_at', sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint('id'),
        sa.UniqueConstraint('short_code')
        )
    if 'wide_limit_day' not in existing:
        op.create_table('wide_limit_day',
        sa.Column('id', sa.Integer(), autoincrement=True, nullable=False),
        sa.Column('trade_date', sa.Date(), nullable=False),
        sa.Column('stock_code', sa.String(length=12), nullable=False),
        sa.Column('created_at', sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint('id'),
        sa.UniqueConstraint('trade_date', 'stock_code', name='uk_wide_limit_day')
        )
    if 'yahoo_minute_day' not in existing:
        op.create_table('yahoo_minute_day',
        sa.Column('symbol', sa.String(length=20), nullable=False),
        sa.Column('trade_date', sa.Date(), nullable=False),
        sa.Column('candles', sa.JSON(), nullable=False),
        sa.PrimaryKeyConstraint('symbol', 'trade_date')
        )


def downgrade() -> None:
    """되돌리지 않는다. 테이블을 모두 지우면 브로커에서 다시 받을 수 없는 과거 데이터가 사라진다."""
    raise RuntimeError("기준선은 되돌릴 수 없다")
