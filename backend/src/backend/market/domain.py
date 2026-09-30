"""시황 도메인 — 장중 누적 스냅샷 엔티티 + 세션 diff 산술용 값 객체.

**핵심 개념**: 키움 ka10051·ka90010과 KIS 선물 투자자 API는 모두 "당일 누적"을 준다.
세션(오전/오후/마감) 순매수는 값을 직접 주는 API가 없어, 폴러가 찍어둔 경계 스냅샷끼리
빼서 만든다. 그래서 아래 값 객체들이 뺄셈을 지원한다.
"""

from dataclasses import dataclass
from datetime import date as date_type
from datetime import datetime

from sqlalchemy import JSON, BigInteger, Boolean, Date, DateTime, Enum, Index, String
from sqlalchemy.orm import Mapped, mapped_column

from backend.library.db import Base
from backend.stock.domain import Market


class FuturesInvestorSnapshot(Base):
    """지수선물 시장의 장중 투자자 순매수 스냅샷. 단위는 계약(부호 포함, 양수=순매수)."""

    __tablename__ = "futures_investor_snapshot"
    __table_args__ = (
        Index("idx_futures_investor_snapshot_lookup", "market", "trade_date", "captured_at"),
    )

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    market: Mapped[Market] = mapped_column(Enum(Market), nullable=False)
    trade_date: Mapped[date_type] = mapped_column(Date, nullable=False)
    captured_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    foreign_qty: Mapped[int] = mapped_column(BigInteger, nullable=False)
    institution_qty: Mapped[int] = mapped_column(BigInteger, nullable=False)
    individual_qty: Mapped[int] = mapped_column(BigInteger, nullable=False)
    # 기관 세부 — 기관계의 내역
    securities_qty: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    insurance_qty: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    merchant_bank_qty: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    trust_qty: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    private_equity_qty: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    fund_qty: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    bank_qty: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    other_org_qty: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    other_corp_qty: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)

    def nets(self) -> "FuturesNets":
        return FuturesNets(
            foreign=self.foreign_qty,
            institution=self.institution_qty,
            individual=self.individual_qty,
            other_corp=self.other_corp_qty,
            breakdown=FuturesOrg(
                securities=self.securities_qty,
                insurance=self.insurance_qty,
                merchant_bank=self.merchant_bank_qty,
                trust=self.trust_qty,
                private_equity=self.private_equity_qty,
                fund=self.fund_qty,
                bank=self.bank_qty,
                other_org=self.other_org_qty,
            ),
        )


@dataclass(frozen=True)
class SessionOrg:
    """세션 기관 세부(억원) — 표시 순서: 금융투자·보험·기타금융·투신·사모펀드·연기금등·은행."""

    financial_investment_eok: int
    insurance_eok: int
    other_finance_eok: int
    trust_eok: int
    private_equity_eok: int
    pension_fund_eok: int
    bank_eok: int

    def __sub__(self, o: "SessionOrg") -> "SessionOrg":
        return SessionOrg(
            self.financial_investment_eok - o.financial_investment_eok,
            self.insurance_eok - o.insurance_eok,
            self.other_finance_eok - o.other_finance_eok,
            self.trust_eok - o.trust_eok,
            self.private_equity_eok - o.private_equity_eok,
            self.pension_fund_eok - o.pension_fund_eok,
            self.bank_eok - o.bank_eok,
        )


@dataclass(frozen=True)
class Nets:
    """투자자별 순매수(억원) 묶음 — 세션 diff 산술용."""

    individual: int
    foreign: int
    institution: int
    other_corp: int
    breakdown: SessionOrg

    def __sub__(self, o: "Nets") -> "Nets":
        return Nets(
            self.individual - o.individual,
            self.foreign - o.foreign,
            self.institution - o.institution,
            self.other_corp - o.other_corp,
            self.breakdown - o.breakdown,
        )


@dataclass(frozen=True)
class FuturesOrg:
    """세션 기관 세부(계약) — 표시 순서: 증권·보험·종금·투신·사모펀드·기금·은행·기타단체."""

    securities: int
    insurance: int
    merchant_bank: int
    trust: int
    private_equity: int
    fund: int
    bank: int
    other_org: int

    def __sub__(self, o: "FuturesOrg") -> "FuturesOrg":
        return FuturesOrg(
            self.securities - o.securities,
            self.insurance - o.insurance,
            self.merchant_bank - o.merchant_bank,
            self.trust - o.trust,
            self.private_equity - o.private_equity,
            self.fund - o.fund,
            self.bank - o.bank,
            self.other_org - o.other_org,
        )


@dataclass(frozen=True)
class FuturesNets:
    foreign: int
    institution: int
    individual: int
    other_corp: int
    breakdown: FuturesOrg

    def __sub__(self, o: "FuturesNets") -> "FuturesNets":
        return FuturesNets(
            self.foreign - o.foreign,
            self.institution - o.institution,
            self.individual - o.individual,
            self.other_corp - o.other_corp,
            self.breakdown - o.breakdown,
        )



class IndexMinuteDay(Base):
    """완전히 수집된 지난 거래일의 지수 OHLCV. 부분 수집·당일 봉은 저장하지 않는다."""

    __tablename__ = "index_minute_day"

    market: Mapped[Market] = mapped_column(Enum(Market), primary_key=True)
    trade_date: Mapped[date_type] = mapped_column(Date, primary_key=True)
    candles: Mapped[list[dict]] = mapped_column(JSON, nullable=False)


class FuturesMinuteDay(Base):
    """완성된 지난 정규장 거래일 또는 야간 세션의 근월물 분봉."""

    __tablename__ = "futures_minute_day"

    market: Mapped[Market] = mapped_column(Enum(Market), primary_key=True)
    contract_code: Mapped[str] = mapped_column(String(20), primary_key=True)
    night: Mapped[bool] = mapped_column(Boolean, primary_key=True)
    trade_date: Mapped[date_type] = mapped_column(Date, primary_key=True)
    candles: Mapped[list[dict]] = mapped_column(JSON, nullable=False)


class YahooMinuteDay(Base):
    """미국 시장의 완성된 지난 거래일 1분봉. 날짜는 뉴욕 현지 거래일이다."""

    __tablename__ = "yahoo_minute_day"

    symbol: Mapped[str] = mapped_column(String(20), primary_key=True)
    trade_date: Mapped[date_type] = mapped_column(Date, primary_key=True)
    candles: Mapped[list[dict]] = mapped_column(JSON, nullable=False)
