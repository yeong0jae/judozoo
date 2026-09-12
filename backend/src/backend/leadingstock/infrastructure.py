"""M5 스냅샷 테이블 중 **M4가 읽어야 하는 것만** 선행.

`market_investor_snapshot`은 M5 폴러가 적재하지만, 시황분석의 세션별 수급(`market.sessions`)이
경계 스냅샷으로 읽는다. 적재 쪽(M5 폴러)은 아직 Kotlin이 담당하므로 여기선 **조회만** 한다.
"""

from datetime import date as date_type
from datetime import datetime

from sqlalchemy import BigInteger, Date, DateTime, Enum, Index, select
from sqlalchemy.orm import Mapped, Session, mapped_column

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
