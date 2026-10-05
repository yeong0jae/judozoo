"""현물 지수의 최근 거래일 수급 — 폴러가 쌓은 스냅샷에서 거래일마다 마지막 값을 읽는다."""

from datetime import date, datetime

import pytest

from backend.leadingstock.infrastructure import MarketInvestorSnapshot
from backend.library.db import get_engine, get_session_factory
from backend.market import application
from backend.stock.domain import Market


@pytest.fixture
def 빈_스냅샷_테이블(통합_db):
    MarketInvestorSnapshot.__table__.create(get_engine(), checkfirst=True)
    with get_session_factory()() as session:
        session.query(MarketInvestorSnapshot).delete()
        session.commit()
        yield session


@pytest.fixture
def 모두_개장일(monkeypatch):
    monkeypatch.setattr(application.calendar, "is_open", lambda _d: True)


def 스냅샷(market: Market, at: datetime, 외인: int) -> MarketInvestorSnapshot:
    return MarketInvestorSnapshot(
        market=market, trade_date=at.date(), captured_at=at,
        foreign_eok=외인, institution_eok=외인 + 1, individual_eok=외인 + 2, other_corp_eok=외인 + 3,
        financial_investment_eok=1, trust_eok=2, pension_fund_eok=3,
        private_equity_eok=4, insurance_eok=5, bank_eok=6, other_finance_eok=7,
        index_value=0, change_rate=0, created_at=at, updated_at=at,
    )


@pytest.mark.integration
class Test최근_거래일_수급:
    def test_거래일마다_마지막_스냅샷을_최신순으로_준다(self, 빈_스냅샷_테이블, 모두_개장일, monkeypatch):
        monkeypatch.setattr(application, "today", lambda: date(2026, 10, 2))
        s = 빈_스냅샷_테이블
        s.add_all([
            스냅샷(Market.KOSPI, datetime(2026, 9, 30, 10, 0), 1),
            스냅샷(Market.KOSPI, datetime(2026, 9, 30, 20, 0), 30),
            스냅샷(Market.KOSPI, datetime(2026, 10, 1, 20, 0), 40),
            스냅샷(Market.KOSPI, datetime(2026, 10, 2, 20, 0), 50),
            스냅샷(Market.KOSDAQ, datetime(2026, 10, 2, 20, 0), 999),
        ])
        s.commit()

        days = application.investor_daily_history(s, Market.KOSPI, 2)

        assert [(d.date, d.foreign_eok) for d in days] == [(date(2026, 10, 2), 50), (date(2026, 10, 1), 40)]
        assert days[0].breakdown.pension_fund_eok == 3

    def test_휴장일에_찍힌_스냅샷은_건너뛴다(self, 빈_스냅샷_테이블, monkeypatch):
        monkeypatch.setattr(application, "today", lambda: date(2026, 10, 2))
        monkeypatch.setattr(application.calendar, "is_open", lambda d: d != date(2026, 10, 1))
        s = 빈_스냅샷_테이블
        s.add_all([
            스냅샷(Market.KOSPI, datetime(2026, 9, 30, 20, 0), 30),
            스냅샷(Market.KOSPI, datetime(2026, 10, 1, 20, 0), 40),
            스냅샷(Market.KOSPI, datetime(2026, 10, 2, 20, 0), 50),
        ])
        s.commit()

        assert [d.date for d in application.investor_daily_history(s, Market.KOSPI, 2)] == [
            date(2026, 10, 2), date(2026, 9, 30),
        ]

    def test_오늘_값이_전일과_같으면_키움이_되돌린_전일_값이라_오늘을_뺀다(self, 빈_스냅샷_테이블, 모두_개장일, monkeypatch):
        monkeypatch.setattr(application, "today", lambda: date(2026, 10, 2))
        s = 빈_스냅샷_테이블
        s.add_all([
            스냅샷(Market.KOSPI, datetime(2026, 9, 30, 20, 0), 30),
            스냅샷(Market.KOSPI, datetime(2026, 10, 1, 20, 0), 40),
            스냅샷(Market.KOSPI, datetime(2026, 10, 2, 8, 1), 40),
        ])
        s.commit()

        assert [d.date for d in application.investor_daily_history(s, Market.KOSPI, 2)] == [
            date(2026, 10, 1), date(2026, 9, 30),
        ]
