"""해외 종목 지난 거래일 1분봉 — 국내 `stock_minute_candle`과 같은 방식이다."""

from datetime import date as date_type
from datetime import datetime

from sqlalchemy import BigInteger, Date, DateTime, Float, Index, String, UniqueConstraint, delete, insert, select
from sqlalchemy.orm import Mapped, Session, mapped_column

from backend.library.db import Base
from backend.platform.kis.overseas_chart import OverseasMinuteCandle


class OverseasMinuteCandleEntity(Base):
    """해외 종목 지난 거래일 1분봉. **완성된 날만** 들어온다 — 하루치를 통째로 갈아 끼운다.

    `trading_day`는 미국 현지 영업일, `minute_at`은 한국 시각이다(`OverseasMinuteCandle`과 같다).
    미국 장은 한국 자정을 넘나들어 한국 날짜로는 하루가 쪼개지므로 날은 현지 영업일로 가른다.
    """

    __tablename__ = "overseas_minute_candle"
    __table_args__ = (
        UniqueConstraint("exchange", "symbol", "minute_at", name="uk_overseas_minute_candle"),
        Index("idx_overseas_minute_candle_day", "exchange", "symbol", "trading_day"),
        Index("idx_overseas_minute_candle_trading_day", "trading_day"),
    )

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    exchange: Mapped[str] = mapped_column(String(8), nullable=False)
    symbol: Mapped[str] = mapped_column(String(16), nullable=False)
    trading_day: Mapped[date_type] = mapped_column(Date, nullable=False)
    minute: Mapped[datetime] = mapped_column("minute_at", DateTime, nullable=False)
    open: Mapped[float] = mapped_column(Float, nullable=False)
    high: Mapped[float] = mapped_column(Float, nullable=False)
    low: Mapped[float] = mapped_column(Float, nullable=False)
    close: Mapped[float] = mapped_column(Float, nullable=False)
    volume: Mapped[int] = mapped_column(BigInteger, nullable=False)
    trading_value: Mapped[float] = mapped_column(Float, nullable=False)


def _of(exchange: str, symbol: str):
    return (OverseasMinuteCandleEntity.exchange == exchange, OverseasMinuteCandleEntity.symbol == symbol)


def save_day(session: Session, exchange: str, symbol: str, day: date_type, bars: list[OverseasMinuteCandle]) -> None:
    """그날 하루치를 통째로 갈아 끼운다. 같은 날이 두 번 들어와도 봉이 겹치지 않는다."""
    session.execute(delete(OverseasMinuteCandleEntity).where(*_of(exchange, symbol), OverseasMinuteCandleEntity.trading_day == day))
    session.execute(
        insert(OverseasMinuteCandleEntity),
        [
            {
                "exchange": exchange, "symbol": symbol, "trading_day": day, "minute": c.date_time,
                "open": c.open, "high": c.high, "low": c.low, "close": c.close,
                "volume": c.volume, "trading_value": c.trading_value,
            }
            for c in bars
        ],
    )
    session.commit()


def load_day(session: Session, exchange: str, symbol: str, day: date_type) -> list[OverseasMinuteCandle]:
    """그날 봉(시각 오름차순). 없으면 빈 목록."""
    rows = session.scalars(
        select(OverseasMinuteCandleEntity)
        .where(*_of(exchange, symbol), OverseasMinuteCandleEntity.trading_day == day)
        .order_by(OverseasMinuteCandleEntity.minute)
    )
    return [
        OverseasMinuteCandle(
            date_time=r.minute, trading_day=r.trading_day, open=r.open, high=r.high, low=r.low, close=r.close,
            volume=r.volume, trading_value=r.trading_value,
        )
        for r in rows
    ]


def latest_days(session: Session, exchange: str, symbol: str, limit: int) -> list[date_type]:
    """들고 있는 날 중 최근 것부터 `limit`개."""
    return list(
        session.scalars(
            select(OverseasMinuteCandleEntity.trading_day)
            .where(*_of(exchange, symbol))
            .distinct()
            .order_by(OverseasMinuteCandleEntity.trading_day.desc())
            .limit(limit)
        )
    )


def purge_before(session: Session, day: date_type) -> int:
    """`day` 전의 봉을 지운다. 지운 행 수를 돌려준다."""
    result = session.execute(delete(OverseasMinuteCandleEntity).where(OverseasMinuteCandleEntity.trading_day < day))
    session.commit()
    return result.rowcount
