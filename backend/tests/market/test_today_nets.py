"""홈 오늘의 수급은 시장 폴러의 스냅샷과 선물 캐시를 합친다."""

from datetime import datetime

import pytest

from backend.leadingstock.infrastructure import MarketInvestorSnapshot
from backend.library.db import get_engine, get_session_factory
from backend.market import application
from backend.stock.domain import Market

AT = datetime(2026, 9, 30, 13, 31)


@pytest.fixture
def 빈_스냅샷_테이블(통합_db):
    MarketInvestorSnapshot.__table__.create(get_engine(), checkfirst=True)
    with get_session_factory()() as session:
        session.query(MarketInvestorSnapshot).delete()
        session.commit()
        yield session


def 스냅샷(market: Market, at: datetime, *, 개인: int, 지수: float) -> MarketInvestorSnapshot:
    return MarketInvestorSnapshot(
        market=market, trade_date=at.date(), captured_at=at,
        individual_eok=개인, foreign_eok=20, institution_eok=-10, other_corp_eok=5,
        financial_investment_eok=0, trust_eok=0, pension_fund_eok=0,
        private_equity_eok=0, insurance_eok=0, bank_eok=0, other_finance_eok=0,
        index_value=지수, change_rate=1.24, created_at=at, updated_at=at,
    )


@pytest.mark.integration
class Test오늘의_수급:
    def test_두_시장의_가장_최근_스냅샷을_읽고_키움_수급을_다시_조회하지_않는다(
        self, 빈_스냅샷_테이블, mocker
    ):
        session = 빈_스냅샷_테이블
        mocker.patch.object(application, "now", return_value=AT)
        외부수급 = mocker.patch.object(application.kiwoom_sector, "fetch_sector_net_buy")
        mocker.patch.object(application, "futures_quote", return_value=None)
        session.add_all([
            스냅샷(Market.KOSPI, AT.replace(minute=29), 개인=100, 지수=2600),
            스냅샷(Market.KOSPI, AT.replace(minute=30), 개인=200, 지수=2653.81),
            스냅샷(Market.KOSDAQ, AT.replace(minute=30), 개인=-300, 지수=900.12),
        ])
        session.commit()

        결과 = application.today_nets(session)

        assert [(n.market, n.futures) for n in 결과] == [
            (Market.KOSPI, False), (Market.KOSDAQ, False),
        ]
        assert (결과[0].index_value, 결과[0].change_rate) == (2653.81, 1.24)
        assert (결과[0].nets.individual, 결과[0].nets.foreign,
                결과[0].nets.institution, 결과[0].nets.other_corp) == (200, 20, -10, 5)
        assert 결과[1].nets.individual == -300
        외부수급.assert_not_called()

    def test_지난날_수급은_오늘_카드에_섞이지_않는다(self, 빈_스냅샷_테이블, mocker):
        session = 빈_스냅샷_테이블
        mocker.patch.object(application, "now", return_value=AT)
        mocker.patch.object(application, "get_kospi", return_value=application.IndexResult(2700, 0.5))
        mocker.patch.object(application, "get_kosdaq", return_value=application.IndexResult(910, -0.2))
        mocker.patch.object(application, "futures_quote", return_value=None)
        session.add(스냅샷(Market.KOSPI, AT.replace(day=29), 개인=999, 지수=2500))
        session.commit()

        결과 = application.today_nets(session)

        assert [(n.index_value, n.nets) for n in 결과] == [(2700, None), (910, None)]

    def test_선물은_기존_캐시_값과_함께_보여준다(self, 빈_스냅샷_테이블, mocker):
        session = 빈_스냅샷_테이블
        mocker.patch.object(application, "now", return_value=AT)
        mocker.patch.object(application, "get_kospi", return_value=application.IndexResult(2700, 0.5))
        mocker.patch.object(application, "get_kosdaq", return_value=application.IndexResult(910, -0.2))
        mocker.patch.object(
            application, "futures_quote", return_value=application.FuturesQuote(
                futures_price=943.2, change_rate=2.71, spot=0, basis=0, dprt=0,
                open_interest=0, open_interest_change=0, rmnn_days=0,
                expiry_date="2026-10-01", investors=application.FuturesInvestorsSummary(
                    foreign=12480, individual=-4210, institution=-7980, other_corp=-290,
                ),
            ),
        )

        결과 = application.today_nets(session)

        assert [(n.market, n.futures) for n in 결과] == [
            (Market.KOSPI, False), (Market.KOSDAQ, False),
            (Market.KOSPI, True), (Market.KOSDAQ, True),
        ]
        assert (결과[2].index_value, 결과[2].change_rate) == (943.2, 2.71)
        assert (결과[2].nets.individual, 결과[2].nets.foreign,
                결과[2].nets.institution, 결과[2].nets.other_corp) == (-4210, 12480, -7980, -290)

    def test_시세와_스냅샷이_모두_없으면_빈_목록이다(self, 빈_스냅샷_테이블, mocker):
        mocker.patch.object(application, "now", return_value=AT)
        mocker.patch.object(application, "get_kospi", side_effect=RuntimeError("시세 실패"))
        mocker.patch.object(application, "get_kosdaq", side_effect=RuntimeError("시세 실패"))
        mocker.patch.object(application, "futures_quote", return_value=None)

        assert application.today_nets(빈_스냅샷_테이블) == []
