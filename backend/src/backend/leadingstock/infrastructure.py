"""주도주 쪽 테이블 — 시장 투자자 스냅샷(조회만), 지난 거래일 종목 1분봉.

`market_investor_snapshot`은 M5 폴러가 적재하지만, 시황분석의 세션별 수급(`market.sessions`)이
경계 스냅샷으로 읽는다. 적재 쪽(M5 폴러)은 아직 Kotlin이 담당하므로 여기선 **조회만** 한다.

`stock_minute_candle`은 지난 날 분봉 보관소(`minute_archive`)의 영속 층이다. 완성된 날만 들어온다.
"""

from datetime import date as date_type
from datetime import datetime

from sqlalchemy import BigInteger, Date, DateTime, Enum, Float, Index, String, UniqueConstraint, delete, insert, select
from sqlalchemy.orm import Mapped, Session, mapped_column

from backend.leadingstock.domain import MinuteCandle
from backend.library.db import Base
from backend.market.domain import Nets, SessionOrg
from backend.stock.domain import Market


class MarketInvestorSnapshot(Base):
    """한 시장의 장중 투자자 순매수 스냅샷. 단위는 억원(부호 포함)."""

    __tablename__ = "market_investor_snapshot"
    __table_args__ = (
        Index("idx_market_investor_snapshot_lookup", "market", "trade_date", "captured_at"),
    )

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    market: Mapped[Market] = mapped_column(Enum(Market), nullable=False)
    trade_date: Mapped[date_type] = mapped_column(Date, nullable=False)
    captured_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    foreign_eok: Mapped[int] = mapped_column(BigInteger, nullable=False)
    institution_eok: Mapped[int] = mapped_column(BigInteger, nullable=False)
    individual_eok: Mapped[int] = mapped_column(BigInteger, nullable=False)
    other_corp_eok: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    # 기관 세부 — 컬럼 추가 이후 폴부터 채워진다(과거 스냅샷은 0).
    financial_investment_eok: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    trust_eok: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    pension_fund_eok: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    private_equity_eok: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    insurance_eok: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    bank_eok: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    other_finance_eok: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    # 캡처 시점 지수 레벨과 등락률. 컬럼이 NOT NULL이라 빼고 적재하면 스냅샷 전체가 실패한다.
    index_value: Mapped[float] = mapped_column(Float, nullable=False)
    change_rate: Mapped[float] = mapped_column(Float, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)

    def nets(self) -> Nets:
        return Nets(
            individual=self.individual_eok,
            foreign=self.foreign_eok,
            institution=self.institution_eok,
            other_corp=self.other_corp_eok,
            breakdown=SessionOrg(
                financial_investment_eok=self.financial_investment_eok,
                insurance_eok=self.insurance_eok,
                other_finance_eok=self.other_finance_eok,
                trust_eok=self.trust_eok,
                private_equity_eok=self.private_equity_eok,
                pension_fund_eok=self.pension_fund_eok,
                bank_eok=self.bank_eok,
            ),
        )


def investor_snapshot_at(
    session: Session, market: Market, at: datetime
) -> MarketInvestorSnapshot | None:
    """한 시장의 `at` 이하 가장 가까운 순매수 스냅샷 — 세션 경계값 조회용."""
    return session.scalar(
        select(MarketInvestorSnapshot)
        .where(
            MarketInvestorSnapshot.market == market,
            MarketInvestorSnapshot.trade_date == at.date(),
            MarketInvestorSnapshot.captured_at <= at,
        )
        .order_by(MarketInvestorSnapshot.captured_at.desc())
        .limit(1)
    )


def latest_investor_snapshot(
    session: Session, market: Market, at: datetime
) -> MarketInvestorSnapshot | None:
    """한 시장의 `at` 이하 가장 최근 순매수 스냅샷 — 날짜를 가리지 않는다.

    장 전·휴장일엔 직전 거래일의 마지막 스냅샷이 나온다. 거래일과 시각이 함께 늘어나므로
    (trade_date, captured_at) 역순이 곧 시각 역순이라 인덱스를 거꾸로 읽고 한 행에서 멈춘다.
    """
    return session.scalar(
        select(MarketInvestorSnapshot)
        .where(MarketInvestorSnapshot.market == market, MarketInvestorSnapshot.captured_at <= at)
        .order_by(MarketInvestorSnapshot.trade_date.desc(), MarketInvestorSnapshot.captured_at.desc())
        .limit(1)
    )


class StockMinuteCandleEntity(Base):
    """지난 거래일 종목 1분봉. **완성된 날만** 들어온다 — 하루치를 통째로 갈아 끼운다.

    배포마다 메모리 보관소가 비어, 다음 날 아침 감시 풀 전체의 어제 봉을 키움에서 다시 받았다.
    여기 두면 재시작해도 남는다. 수정주가 소급은 반영되지 않으므로 짧게 두고 지운다(`purge_minute_days_before`).
    """

    __tablename__ = "stock_minute_candle"
    __table_args__ = (
        UniqueConstraint("stock_code", "minute_at", name="uk_stock_minute_candle"),
        Index("idx_stock_minute_candle_day", "stock_code", "trade_date"),
        Index("idx_stock_minute_candle_trade_date", "trade_date"),
    )

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    stock_code: Mapped[str] = mapped_column(String(12), nullable=False)
    trade_date: Mapped[date_type] = mapped_column(Date, nullable=False)
    minute: Mapped[datetime] = mapped_column("minute_at", DateTime, nullable=False)
    open_price: Mapped[int] = mapped_column(BigInteger, nullable=False)
    high_price: Mapped[int] = mapped_column(BigInteger, nullable=False)
    low_price: Mapped[int] = mapped_column(BigInteger, nullable=False)
    close_price: Mapped[int] = mapped_column(BigInteger, nullable=False)
    volume: Mapped[int] = mapped_column(BigInteger, nullable=False)
    trading_value: Mapped[int] = mapped_column(BigInteger, nullable=False)


def save_minute_day(session: Session, stock_code: str, day: date_type, bars: list[MinuteCandle]) -> None:
    """그날 하루치를 통째로 갈아 끼운다. 같은 날이 두 번 들어와도 봉이 겹치지 않는다."""
    session.execute(
        delete(StockMinuteCandleEntity).where(
            StockMinuteCandleEntity.stock_code == stock_code, StockMinuteCandleEntity.trade_date == day
        )
    )
    session.execute(
        insert(StockMinuteCandleEntity),
        [
            {
                "stock_code": stock_code, "trade_date": day, "minute": c.date_time,
                "open_price": c.open_price, "high_price": c.high_price, "low_price": c.low_price,
                "close_price": c.close_price, "volume": c.volume, "trading_value": c.trading_value,
            }
            for c in bars
        ],
    )
    session.commit()


def load_minute_day(session: Session, stock_code: str, day: date_type) -> list[MinuteCandle]:
    """그날 봉(시각 오름차순). 없으면 빈 목록."""
    rows = session.scalars(
        select(StockMinuteCandleEntity)
        .where(StockMinuteCandleEntity.stock_code == stock_code, StockMinuteCandleEntity.trade_date == day)
        .order_by(StockMinuteCandleEntity.minute)
    )
    return [
        MinuteCandle(
            date_time=r.minute, open_price=r.open_price, high_price=r.high_price, low_price=r.low_price,
            close_price=r.close_price, volume=r.volume, trading_value=r.trading_value,
        )
        for r in rows
    ]


def purge_minute_days_before(session: Session, day: date_type) -> int:
    """`day` 전의 봉을 지운다. 지운 행 수를 돌려준다."""
    result = session.execute(delete(StockMinuteCandleEntity).where(StockMinuteCandleEntity.trade_date < day))
    session.commit()
    return result.rowcount
