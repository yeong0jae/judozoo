"""현물 지수의 최근 거래일 수급 — 키움이 직전 거래일 값을 되돌리는 경계."""

from datetime import date, datetime

from backend.market import application
from backend.platform.kiwoom.sector_investor import SectorInvestorNetBuy
from backend.stock.domain import Market


def 수급(value: int) -> SectorInvestorNetBuy:
    return SectorInvestorNetBuy(
        foreign_eok=value, institution_eok=value + 1, individual_eok=value + 2,
        other_corp_eok=value + 3, financial_investment_eok=value + 4,
        trust_eok=value + 5, pension_fund_eok=value + 6,
        private_equity_eok=value + 7, insurance_eok=value + 8,
        bank_eok=value + 9, other_finance_eok=value + 10,
        index_value=0, change_rate=0,
    )


class Test최근_거래일_수급:
    def test_자정_직후에는_오늘을_조회하지_않는다(self, mocker):
        mocker.patch.object(application, "today", return_value=date(2026, 10, 1))
        mocker.patch.object(application, "now", return_value=datetime(2026, 10, 1, 0, 30))
        mocker.patch.object(application.calendar, "is_open", return_value=True)
        fetched = mocker.patch.object(
            application.kiwoom_sector, "fetch_sector_net_buy",
            side_effect=lambda _market, day: {"20260930": 수급(10), "20260929": 수급(20)}[day],
        )

        days = application.investor_daily_history(Market.KOSPI, 2)

        assert [d.date for d in days] == [date(2026, 9, 30), date(2026, 9, 29)]
        assert [call.args[1] for call in fetched.call_args_list] == ["20260930", "20260929"]

    def test_장_시작_직후_전일_값이_그대로_오면_오늘_행을_뺀다(self, mocker):
        mocker.patch.object(application, "today", return_value=date(2026, 10, 1))
        mocker.patch.object(application, "now", return_value=datetime(2026, 10, 1, 8, 1))
        mocker.patch.object(application.calendar, "is_open", return_value=True)
        fetched = mocker.patch.object(
            application.kiwoom_sector, "fetch_sector_net_buy",
            side_effect=lambda _market, day: {
                "20261001": 수급(10), "20260930": 수급(10), "20260929": 수급(20),
            }[day],
        )

        days = application.investor_daily_history(Market.KOSDAQ, 2)

        assert [d.date for d in days] == [date(2026, 9, 30), date(2026, 9, 29)]
        assert [call.args[1] for call in fetched.call_args_list] == ["20261001", "20260930", "20260929"]

    def test_오늘_수급이_실제로_다르면_오늘_행을_유지한다(self, mocker):
        mocker.patch.object(application, "today", return_value=date(2026, 10, 1))
        mocker.patch.object(application, "now", return_value=datetime(2026, 10, 1, 9, 0))
        mocker.patch.object(application.calendar, "is_open", return_value=True)
        mocker.patch.object(
            application.kiwoom_sector, "fetch_sector_net_buy",
            side_effect=lambda _market, day: {"20261001": 수급(30), "20260930": 수급(10)}[day],
        )

        days = application.investor_daily_history(Market.KOSPI, 2)

        assert [d.date for d in days] == [date(2026, 10, 1), date(2026, 9, 30)]
        assert days[0].foreign_eok == 30

    def test_한_건만_요청해도_전일_값과_비교한다(self, mocker):
        mocker.patch.object(application, "today", return_value=date(2026, 10, 1))
        mocker.patch.object(application, "now", return_value=datetime(2026, 10, 1, 8, 1))
        mocker.patch.object(application.calendar, "is_open", return_value=True)
        mocker.patch.object(
            application.kiwoom_sector, "fetch_sector_net_buy",
            side_effect=lambda _market, day: {"20261001": 수급(10), "20260930": 수급(10)}[day],
        )

        assert [d.date for d in application.investor_daily_history(Market.KOSPI, 1)] == [date(2026, 9, 30)]
