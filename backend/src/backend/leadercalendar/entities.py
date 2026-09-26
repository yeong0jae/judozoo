"""주도주 캘린더 엔티티 — 홈 주도주 카드가 마감 때 고른 종목을 날짜별로 남긴다.

날(`LeaderDay`)과 종목(`LeaderDayStock`)을 나눈 이유 — 주도주가 0개인 날이 있다. 날 행만 있고
종목 행이 없으면 "주도주 없음"(`closed`면 휴장), 날 행이 없으면 "기록 없음"이다.
종목 테이블 하나로는 셋이 갈리지 않는다.

FK는 걸지 않는다 — 코드베이스 관례다(V007). 날을 지울 때 종목도 같은 트랜잭션에서 지운다.
"""

from datetime import date, datetime
from decimal import Decimal

from sqlalchemy import BigInteger, Boolean, Date, DateTime, Enum, Float, Index, Numeric, SmallInteger, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from backend.leadingstock.domain import LeadingStockSnapshot
from backend.library.db import Base
from backend.market.calendar import Region
from backend.overseasleadingstock.domain import OverseasStockRank


class LeaderDay(Base):
    """한 시장의 한 거래일. `trade_date`는 **현지 날짜**다 — US는 뉴욕 기준."""

    __tablename__ = "leader_day"
    __table_args__ = (
        UniqueConstraint("region", "trade_date", name="uk_leader_day"),
        Index("idx_leader_day_date", "trade_date"),
    )

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    region: Mapped[Region] = mapped_column(Enum(Region, length=2), nullable=False)
    trade_date: Mapped[date] = mapped_column(Date, nullable=False)
    taken_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    #: 휴장일 — 종목 행이 없다. 이름(추석 등)은 휴장 판정 API가 주지 않아 남기지 않는다
    closed: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)

    @classmethod
    def taken(cls, region: Region, trade_date: date, at: datetime, closed: bool = False) -> "LeaderDay":
        return cls(region=region, trade_date=trade_date, taken_at=at, closed=closed, created_at=at, updated_at=at)


class LeaderDayStock(Base):
    """그날 뽑힌 종목 한 줄. 가격·거래대금 단위는 날의 `region`으로 읽는다 — KR 원, US 달러."""

    __tablename__ = "leader_day_stock"
    __table_args__ = (
        UniqueConstraint("leader_day_id", "rank", name="uk_leader_day_stock"),
        Index("idx_leader_day_stock_code", "code"),
    )

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    leader_day_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    rank: Mapped[int] = mapped_column(SmallInteger, nullable=False)
    #: US만 — NAS / NYS / AMS
    exchange: Mapped[str | None] = mapped_column(String(3), nullable=True)
    #: KR 6자리 단축코드 / US 심볼
    code: Mapped[str] = mapped_column(String(16), nullable=False)
    #: 그날 이름을 박제한다 — 상호가 바뀌어도 그날 화면에 뜬 이름이 남는다
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    price: Mapped[Decimal] = mapped_column(Numeric(18, 4), nullable=False)
    change_rate: Mapped[float] = mapped_column(Float, nullable=False)
    trading_value: Mapped[Decimal] = mapped_column(Numeric(20, 2), nullable=False)

    @classmethod
    def domestic(cls, rank: int, s: LeadingStockSnapshot) -> "LeaderDayStock":
        """키움 코드는 `009150_AL`처럼 거래소 접미사가 붙어 온다 — `stocks.short_code`와 이어지게 앞만 남긴다."""
        return cls(
            rank=rank, exchange=None, code=s.stock_code.split("_", 1)[0], name=s.stock_name,
            price=Decimal(s.current_price), change_rate=s.price_change_rate,
            trading_value=Decimal(s.accumulated_trading_value),
        )

    @classmethod
    def overseas(cls, rank: int, s: OverseasStockRank) -> "LeaderDayStock":
        return cls(
            rank=rank, exchange=s.exchange, code=s.symbol, name=s.name,
            price=Decimal(str(s.price)), change_rate=s.rate,
            trading_value=Decimal(str(round(s.trading_value, 2))),
        )
