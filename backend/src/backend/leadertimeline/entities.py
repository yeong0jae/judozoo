"""주도주 타임라인 엔티티 — 홈 주도주 카드가 고른 5종목을 1분마다 남긴다.

분(`LeaderTick`)과 종목(`LeaderTickStock`)을 나눈 이유 — 024 캘린더와 같다. 주도주가 0개인 분은 분 행만 있고
종목 행이 없다. 분 행이 없으면 "그 분은 못 찍었다"이다. 종목 테이블 하나로는 둘이 갈리지 않는다.

FK는 걸지 않는다 — 코드베이스 관례다(V007). 분을 지울 때 종목도 같은 트랜잭션에서 지운다.
"""

from datetime import date, datetime
from decimal import Decimal

from sqlalchemy import BigInteger, Date, DateTime, Enum, Float, Index, Numeric, SmallInteger, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from backend.leadingstock.domain import LeadingStockSnapshot
from backend.library.db import Base
from backend.market.calendar import Region
from backend.overseasleadingstock.domain import OverseasStockRank


class LeaderTick(Base):
    """한 시장의 한 분. `at`은 **현지 시각**(분 단위)이다 — 해외는 뉴욕 시각."""

    __tablename__ = "leader_tick"
    __table_args__ = (
        UniqueConstraint("region", "at", name="uk_leader_tick"),
        Index("idx_leader_tick_day", "region", "trade_date", "at"),
    )

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    region: Mapped[Region] = mapped_column(Enum(Region, length=2), nullable=False)
    trade_date: Mapped[date] = mapped_column(Date, nullable=False)
    at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    #: 실제로 찍은 시각(KST) — 화면의 "47초 전"이 이 값에서 나온다
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)


class LeaderTickStock(Base):
    """그 분에 뽑힌 종목 한 줄. 가격·거래대금 단위는 분의 `region`으로 읽는다 — KR 원, US 달러."""

    __tablename__ = "leader_tick_stock"
    __table_args__ = (
        UniqueConstraint("leader_tick_id", "rank", name="uk_leader_tick_stock"),
        Index("idx_leader_tick_stock_code", "code"),
    )

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    leader_tick_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    rank: Mapped[int] = mapped_column(SmallInteger, nullable=False)
    #: US만 — NAS / NYS / AMS
    exchange: Mapped[str | None] = mapped_column(String(3), nullable=True)
    #: KR 6자리 단축코드 / US 심볼
    code: Mapped[str] = mapped_column(String(16), nullable=False)
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    price: Mapped[Decimal] = mapped_column(Numeric(18, 4), nullable=False)
    change_rate: Mapped[float] = mapped_column(Float, nullable=False)
    trading_value: Mapped[Decimal] = mapped_column(Numeric(20, 2), nullable=False)

    @classmethod
    def domestic(cls, rank: int, s: LeadingStockSnapshot) -> "LeaderTickStock":
        """키움 코드는 `009150_AL`처럼 거래소 접미사가 붙어 온다 — `stocks.short_code`와 이어지게 앞만 남긴다."""
        return cls(
            rank=rank, exchange=None, code=s.stock_code.split("_", 1)[0], name=s.stock_name,
            price=Decimal(s.current_price), change_rate=s.price_change_rate,
            trading_value=Decimal(s.accumulated_trading_value),
        )

    @classmethod
    def overseas(cls, rank: int, s: OverseasStockRank) -> "LeaderTickStock":
        return cls(
            rank=rank, exchange=s.exchange, code=s.symbol, name=s.name,
            price=Decimal(str(s.price)), change_rate=s.rate,
            trading_value=Decimal(str(round(s.trading_value, 2))),
        )
