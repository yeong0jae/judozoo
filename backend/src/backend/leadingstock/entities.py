"""시그널·스냅샷 영속 엔티티.

`event_type`·`kind`는 **varchar로 고정**한다 — MySQL 네이티브 ENUM으로 두면 enum 값을 추가할 때
`ddl-auto`가 컬럼을 고치지 않아 INSERT가 truncate로 터진다(Kotlin 쪽 주석에 남은 실제 사고).
"""

from datetime import date as date_type
from datetime import datetime

from sqlalchemy import BigInteger, Date, DateTime, Enum, Float, Index, Integer, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from backend.leadingstock.domain import SpikeDirection
from backend.leadingstock.signals import InvestorType, MarketSignalType, NetTradeSide, SignalEventType
from backend.library.db import Base
from backend.stock.domain import Market


class SignalEvent(Base):
    """종목 시그널 전이 한 건.

    전이 순간의 컨텍스트를 함께 박아 둬 재조회 없이 복기할 수 있게 한다.
    `gap_rate`는 돌파 계열에만, 스파이크 3종은 스파이크에만, `ma20`은 돌림에만 채워진다.
    """

    __tablename__ = "signal_event"
    __table_args__ = (Index("idx_signal_event_date", "trade_date"),)

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    occurred_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    trade_date: Mapped[date_type] = mapped_column(Date, nullable=False)
    stock_code: Mapped[str] = mapped_column(String(255), nullable=False)
    stock_name: Mapped[str] = mapped_column(String(255), nullable=False)
    event_type: Mapped[str] = mapped_column(String(32), nullable=False)
    current_price: Mapped[int] = mapped_column(BigInteger, nullable=False)
    price_change_rate: Mapped[float] = mapped_column(Float, nullable=False)
    trading_value: Mapped[int] = mapped_column(BigInteger, nullable=False)
    gap_rate: Mapped[float | None] = mapped_column(Float, nullable=True)
    spike_ratio: Mapped[float | None] = mapped_column(Float, nullable=True)
    minute_trading_value: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    spike_direction: Mapped[SpikeDirection | None] = mapped_column(Enum(SpikeDirection), nullable=True)
    ma20: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    theme: Mapped[str | None] = mapped_column(String(255), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)

    @property
    def type(self) -> SignalEventType:
        return SignalEventType(self.event_type)


class MarketSignalEvent(Base):
    """시장 단위 시그널 전이 한 건. `kind`로 종류를 구분한다."""

    __tablename__ = "market_signal_event"
    __table_args__ = (Index("idx_market_signal_event_date", "trade_date"),)

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    occurred_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    trade_date: Mapped[date_type] = mapped_column(Date, nullable=False)
    kind: Mapped[str] = mapped_column(String(32), nullable=False)
    market: Mapped[Market] = mapped_column(Enum(Market), nullable=False)
    side: Mapped[NetTradeSide] = mapped_column(Enum(NetTradeSide), nullable=False)
    investor: Mapped[InvestorType | None] = mapped_column(Enum(InvestorType), nullable=True)
    level: Mapped[int | None] = mapped_column("step_level", Integer, nullable=True)
    net_amount_eok: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    extreme_amount_eok: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    index_value: Mapped[float | None] = mapped_column(Float, nullable=True)
    change_rate: Mapped[float | None] = mapped_column(Float, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)

    @property
    def signal_kind(self) -> MarketSignalType:
        return MarketSignalType(self.kind)


class MarketCloseSnapshot(Base):
    """장 마감(15:40) 투자자 순매수 스냅샷 — 타임라인에 하루 한 줄.

    (시장, 거래일)별 한 행만 존재한다(같은 날 재캡처는 스킵). 단위는 억원(부호 포함).
    """

    __tablename__ = "market_close_snapshot"
    __table_args__ = (
        UniqueConstraint("market", "trade_date", name="uk_market_close_snapshot"),
    )

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    market: Mapped[Market] = mapped_column(Enum(Market), nullable=False)
    trade_date: Mapped[date_type] = mapped_column(Date, nullable=False)
    captured_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    foreign_eok: Mapped[int] = mapped_column(BigInteger, nullable=False)
    institution_eok: Mapped[int] = mapped_column(BigInteger, nullable=False)
    individual_eok: Mapped[int] = mapped_column(BigInteger, nullable=False)
    index_value: Mapped[float | None] = mapped_column(Float, nullable=True)
    change_rate: Mapped[float | None] = mapped_column(Float, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)


class MarketFlowStateSnapshot(Base):
    """흐름 전환 상태 스냅샷 — 재시작으로 메모리가 비워져도 정점을 복원하기 위함.

    (시장, 투자자)별 한 행을 매 폴 upsert한다. `trade_date`가 다르면 새 날 = 새 추적.
    """

    __tablename__ = "market_flow_state"
    __table_args__ = (UniqueConstraint("market", "investor", name="uk_market_flow_state"),)

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    market: Mapped[Market] = mapped_column(Enum(Market), nullable=False)
    investor: Mapped[InvestorType] = mapped_column(Enum(InvestorType), nullable=False)
    side: Mapped[NetTradeSide] = mapped_column(Enum(NetTradeSide), nullable=False)
    extreme_eok: Mapped[int] = mapped_column(BigInteger, nullable=False)
    trade_date: Mapped[date_type] = mapped_column(Date, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)


class IndexMinuteCandleEntity(Base):
    """영속화된 지수 1분봉. (시장, 분)으로 유일 — 진행 중인 분을 더 완성된 값으로 upsert한다."""

    __tablename__ = "index_minute_candle"
    __table_args__ = (
        UniqueConstraint("market", "minute_at", name="uk_index_minute_candle"),
        Index("idx_index_minute_candle_date", "market", "trade_date"),
    )

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    market: Mapped[Market] = mapped_column(Enum(Market), nullable=False)
    trade_date: Mapped[date_type] = mapped_column(Date, nullable=False)
    minute: Mapped[datetime] = mapped_column("minute_at", DateTime, nullable=False)
    open: Mapped[float] = mapped_column("open_price", Float, nullable=False)
    high: Mapped[float] = mapped_column("high_price", Float, nullable=False)
    low: Mapped[float] = mapped_column("low_price", Float, nullable=False)
    close: Mapped[float] = mapped_column("close_price", Float, nullable=False)
    volume: Mapped[int] = mapped_column(BigInteger, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)


# 시장별 투자자 순매수 시그널의 단계 크기와 완충(억원).
# 코스피는 1조, 코스닥은 1,000억마다 한 단계. 완충은 단계의 10%로 경계 잔떨림을 흡수한다.
_STEP_EOK = {Market.KOSPI: 10_000, Market.KOSDAQ: 1_000}
# 흐름 전환 임계 — 코스닥은 규모가 작아 더 민감하게.
_REVERSAL_EOK = {Market.KOSPI: 1_000, Market.KOSDAQ: 100}


def step_eok(market: Market) -> int:
    return _STEP_EOK[market]


def buffer_eok(market: Market) -> int:
    return _STEP_EOK[market] // 10


def reversal_eok(market: Market) -> int:
    return _REVERSAL_EOK[market]
