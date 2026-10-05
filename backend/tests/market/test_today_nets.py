"""홈 오늘의 수급은 시장 폴러의 스냅샷과 선물 캐시를 합친다."""

from datetime import date, datetime
from types import SimpleNamespace

import pytest

from backend.leadingstock.infrastructure import MarketInvestorSnapshot
from backend.library.db import get_engine, get_session_factory
from backend.market import application
from backend.market.domain import FuturesInvestorSnapshot
from backend.stock.domain import Market

AT = datetime(2026, 9, 30, 13, 31)


@pytest.fixture
def 빈_스냅샷_테이블(통합_db):
    MarketInvestorSnapshot.__table__.create(get_engine(), checkfirst=True)
    FuturesInvestorSnapshot.__table__.create(get_engine(), checkfirst=True)
    with get_session_factory()() as session:
        session.query(MarketInvestorSnapshot).delete()
        session.query(FuturesInvestorSnapshot).delete()
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


def 선물스냅샷(market: Market, at: datetime, *, 개인: int) -> FuturesInvestorSnapshot:
    return FuturesInvestorSnapshot(
        market=market, trade_date=at.date(), captured_at=at,
        individual_qty=개인, foreign_qty=120, institution_qty=-80, other_corp_qty=-40,
        securities_qty=0, insurance_qty=0, merchant_bank_qty=0, trust_qty=0,
        private_equity_qty=0, fund_qty=0, bank_qty=0, other_org_qty=0,
        created_at=at, updated_at=at,
    )


def _선물시세() -> application.FuturesQuote:
    return application.FuturesQuote(
        futures_price=943.2, change_rate=2.71, spot=0, basis=0, dprt=0,
        open_interest=0, open_interest_change=0, rmnn_days=0,
        expiry_date="2026-10-01",
    )


@pytest.mark.integration
class Test오늘의_수급:
    def test_두_시장의_가장_최근_스냅샷을_읽고_키움_수급을_다시_조회하지_않는다(
        self, 빈_스냅샷_테이블, mocker
    ):
        session = 빈_스냅샷_테이블
        mocker.patch.object(application, "now", return_value=AT)
        외부수급 = mocker.patch("backend.platform.kiwoom.sector_investor.fetch_sector_net_buy")
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
        assert {n.trade_date for n in 결과} == {AT.date()}
        외부수급.assert_not_called()

    def test_장중에는_지난날_수급을_오늘_카드에_섞지_않는다(self, 빈_스냅샷_테이블, mocker):
        session = 빈_스냅샷_테이블
        mocker.patch.object(application, "now", return_value=AT)
        mocker.patch.object(application.calendar, "is_holiday", return_value=False)
        mocker.patch.object(application, "get_kospi", return_value=application.IndexResult(2700, 0.5))
        mocker.patch.object(application, "get_kosdaq", return_value=application.IndexResult(910, -0.2))
        mocker.patch.object(application, "futures_quote", return_value=None)
        session.add(스냅샷(Market.KOSPI, AT.replace(day=29), 개인=999, 지수=2500))
        session.commit()

        결과 = application.today_nets(session)

        assert [(n.index_value, n.nets) for n in 결과] == [(2700, None), (910, None)]

    def test_선물은_시세와_오늘_수급_스냅샷을_함께_보여준다(self, 빈_스냅샷_테이블, mocker):
        session = 빈_스냅샷_테이블
        mocker.patch.object(application, "now", return_value=AT)
        mocker.patch.object(application.calendar, "is_holiday", return_value=False)
        mocker.patch.object(application, "get_kospi", return_value=application.IndexResult(2700, 0.5))
        mocker.patch.object(application, "get_kosdaq", return_value=application.IndexResult(910, -0.2))
        mocker.patch.object(
            application, "futures_quote", return_value=application.FuturesQuote(
                futures_price=943.2, change_rate=2.71, spot=0, basis=0, dprt=0,
                open_interest=0, open_interest_change=0, rmnn_days=0,
                expiry_date="2026-10-01",
            ),
        )
        session.add_all([
            선물스냅샷(Market.KOSPI, AT.replace(minute=30), 개인=-4210),
            선물스냅샷(Market.KOSPI, AT, 개인=-4220),
            선물스냅샷(Market.KOSDAQ, AT, 개인=630),
        ])
        session.commit()

        결과 = application.today_nets(session)

        assert [(n.market, n.futures) for n in 결과] == [
            (Market.KOSPI, False), (Market.KOSDAQ, False),
            (Market.KOSPI, True), (Market.KOSDAQ, True),
        ]
        assert (결과[2].index_value, 결과[2].change_rate) == (943.2, 2.71)
        assert (결과[2].nets.individual, 결과[2].nets.foreign,
                결과[2].nets.institution, 결과[2].nets.other_corp) == (-4220, 120, -80, -40)
        assert 결과[3].nets.individual == 630

    def test_장중에_오늘_선물_스냅샷이_없으면_지난날_수급을_표시하지_않는다(self, 빈_스냅샷_테이블, mocker):
        session = 빈_스냅샷_테이블
        mocker.patch.object(application, "now", return_value=AT)
        mocker.patch.object(application.calendar, "is_holiday", return_value=False)
        mocker.patch.object(application, "get_kospi", return_value=application.IndexResult(2700, 0.5))
        mocker.patch.object(application, "get_kosdaq", return_value=application.IndexResult(910, -0.2))
        mocker.patch.object(
            application, "futures_quote", return_value=application.FuturesQuote(
                futures_price=943.2, change_rate=2.71, spot=0, basis=0, dprt=0,
                open_interest=0, open_interest_change=0, rmnn_days=0,
                expiry_date="2026-10-01",
            ),
        )
        session.add(선물스냅샷(Market.KOSPI, AT.replace(day=29), 개인=999))
        session.commit()

        선물들 = [n for n in application.today_nets(session) if n.futures]

        assert len(선물들) == 2
        assert all(n.nets is None and n.index_value == 943.2 for n in 선물들)

    def test_자정_직후에는_직전_거래일_마지막_수급을_그_날짜와_함께_보여준다(self, 빈_스냅샷_테이블, mocker):
        session = 빈_스냅샷_테이블
        mocker.patch.object(application, "now", return_value=datetime(2026, 10, 1, 0, 11))
        mocker.patch.object(application.calendar, "is_holiday", return_value=False)
        mocker.patch.object(application, "get_kospi", return_value=application.IndexResult(2700, 0.5))
        mocker.patch.object(application, "get_kosdaq", return_value=application.IndexResult(910, -0.2))
        mocker.patch.object(application, "futures_quote", return_value=_선물시세())
        session.add_all([
            스냅샷(Market.KOSPI, datetime(2026, 9, 30, 19, 58), 개인=100, 지수=2600),
            스냅샷(Market.KOSPI, datetime(2026, 9, 30, 20, 0), 개인=200, 지수=2653.81),
            스냅샷(Market.KOSDAQ, datetime(2026, 9, 30, 20, 0), 개인=-300, 지수=900.12),
            선물스냅샷(Market.KOSPI, datetime(2026, 9, 30, 15, 45), 개인=-4220),
            선물스냅샷(Market.KOSDAQ, datetime(2026, 9, 30, 15, 45), 개인=630),
        ])
        session.commit()

        결과 = application.today_nets(session)

        assert [(n.market, n.futures, n.nets.individual, n.trade_date) for n in 결과] == [
            (Market.KOSPI, False, 200, date(2026, 9, 30)),
            (Market.KOSDAQ, False, -300, date(2026, 9, 30)),
            (Market.KOSPI, True, -4220, date(2026, 9, 30)),
            (Market.KOSDAQ, True, 630, date(2026, 9, 30)),
        ]
        # 현물 지수도 그 수급과 같은 시점 값이다
        assert 결과[0].index_value == 2653.81

    def test_휴장일에는_장중_시각에도_직전_거래일_수급을_보여준다(self, 빈_스냅샷_테이블, mocker):
        session = 빈_스냅샷_테이블
        mocker.patch.object(application, "now", return_value=datetime(2026, 10, 3, 13, 0))
        mocker.patch.object(application.calendar, "is_holiday", return_value=True)
        mocker.patch.object(application, "futures_quote", return_value=None)
        session.add_all([
            스냅샷(Market.KOSPI, datetime(2026, 10, 2, 20, 0), 개인=200, 지수=2653.81),
            스냅샷(Market.KOSDAQ, datetime(2026, 10, 2, 20, 0), 개인=-300, 지수=900.12),
        ])
        session.commit()

        결과 = application.today_nets(session)

        assert [(n.nets.individual, n.trade_date) for n in 결과] == [
            (200, date(2026, 10, 2)), (-300, date(2026, 10, 2)),
        ]

    def test_오늘_첫_스냅샷이_들어오면_오늘_수급으로_바뀐다(self, 빈_스냅샷_테이블, mocker):
        session = 빈_스냅샷_테이블
        mocker.patch.object(application, "now", return_value=datetime(2026, 10, 1, 8, 1))
        mocker.patch.object(application.calendar, "is_holiday", return_value=False)
        mocker.patch.object(application, "get_kosdaq", return_value=application.IndexResult(910, -0.2))
        mocker.patch.object(application, "futures_quote", return_value=None)
        session.add_all([
            스냅샷(Market.KOSPI, datetime(2026, 9, 30, 20, 0), 개인=200, 지수=2653.81),
            스냅샷(Market.KOSPI, datetime(2026, 10, 1, 8, 0), 개인=7, 지수=2660),
        ])
        session.commit()

        코스피 = application.today_nets(session)[0]

        assert (코스피.nets.individual, 코스피.trade_date) == (7, date(2026, 10, 1))

    def test_선물_시세는_수급과_무관하게_갱신한다(self, 빈_스냅샷_테이블, mocker):
        mocker.patch.object(application.kis_futures, "fetch_near_month", return_value=SimpleNamespace(
            iscd="CONTRACT", name="F 202610", rmnn_days=10,
        ))
        mocker.patch.object(application.kis_futures, "fetch_daily", return_value=SimpleNamespace(
            summary=SimpleNamespace(
                futures_price=943.2, change_rate=2.71, spot=940, basis=3.2, dprt=0,
                open_interest=1000, open_interest_change=10,
            ),
        ))
        외부수급 = mocker.patch.object(application.kis_futures, "fetch_investors")

        시세 = application.futures_quote(Market.KOSPI)

        assert 시세.futures_price == 943.2
        assert not hasattr(시세, "investors")
        외부수급.assert_not_called()

    def test_시세와_스냅샷이_모두_없으면_빈_목록이다(self, 빈_스냅샷_테이블, mocker):
        mocker.patch.object(application, "now", return_value=AT)
        mocker.patch.object(application.calendar, "is_holiday", return_value=False)
        mocker.patch.object(application, "get_kospi", side_effect=RuntimeError("시세 실패"))
        mocker.patch.object(application, "get_kosdaq", side_effect=RuntimeError("시세 실패"))
        mocker.patch.object(application, "futures_quote", return_value=None)

        assert application.today_nets(빈_스냅샷_테이블) == []
