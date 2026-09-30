"""사용자 없이 갱신하고, 만료·외부 장애 중에도 마지막 정상 시세를 제공한다."""
from datetime import datetime
from types import SimpleNamespace

import pytest

from backend.library import cache
from backend.market import application, scheduler
from backend.stock.domain import Market


@pytest.fixture
def 시세(mocker):
    index = mocker.patch.object(application.kiwoom_index, "fetch_index",
                               return_value=SimpleNamespace(current_value=2700, change_rate=1.5))
    mocker.patch.object(application.kis_futures, "fetch_near_month", return_value=None)
    yahoo = mocker.patch.object(application.yahoo, "fetch_quote",
                               return_value=SimpleNamespace(price=100, prev_close=80))
    mocker.patch.object(scheduler, "now", return_value=datetime(2026, 9, 30, 10))
    mocker.patch.object(scheduler.calendar, "is_holiday", return_value=False)
    mocker.patch.object(scheduler.calendar, "is_open", return_value=True)
    return index, yahoo


class Test시세_폴러:
    def test_사용자가_없어도_채우고_장중에는_유효한_캐시도_갱신한다(self, 시세):
        index, _ = 시세
        scheduler.poll_quotes()
        assert application.get_kospi().current_value == 2700
        assert application.get_kosdaq().change_rate == 1.5
        assert index.call_count == 2
        scheduler.poll_quotes()
        assert index.call_count == 4

    def test_TTL이_지나도_요청은_외부를_부르지_않고_실패하면_정상값을_유지한다(self, 시세):
        index, _ = 시세
        scheduler.poll_quotes()
        cache._caches["kospiIndex"].clear()
        index.return_value = None
        assert application.get_kospi().current_value == 2700
        assert index.call_count == 2
        scheduler.poll_quotes()
        assert application.get_kospi().current_value == 2700
        assert index.call_count == 4

    def test_매크로_한_항목이_실패해도_나머지는_갱신한다(self, 시세):
        _, yahoo = 시세
        scheduler.poll_quotes()
        yahoo.side_effect = lambda symbol: None if symbol == "KRW=X" else SimpleNamespace(price=120, prev_close=80)
        scheduler.poll_quotes()
        quotes = application.macro_quotes()
        assert quotes.usd_krw.price == 100
        assert quotes.wti.price == 120

    def test_휴장에는_처음만_채우고_국내_시세를_다시_받지_않는다(self, 시세, mocker):
        index, _ = 시세
        mocker.patch.object(scheduler.calendar, "is_holiday", return_value=True)
        scheduler.poll_quotes()
        scheduler.poll_quotes()
        assert index.call_count == 2

    def test_선물은_실패해도_이전_값을_유지한다(self, mocker):
        mocker.patch.object(application.kis_futures, "fetch_near_month",
                          return_value=SimpleNamespace(iscd="101", rmnn_days=1, name="F 202609"))
        daily = mocker.patch.object(application.kis_futures, "fetch_daily", return_value=SimpleNamespace(
            summary=SimpleNamespace(futures_price=300, change_rate=1, spot=299, basis=1,
                                    dprt=0, open_interest=100, open_interest_change=1)))
        mocker.patch.object(application.kis_futures, "fetch_investors", return_value=None)
        assert application.futures_quote(Market.KOSPI).futures_price == 300
        cache._caches["futuresQuote"].clear()
        daily.return_value = None
        assert application.futures_quote.refresh(Market.KOSPI) is None
        assert application.futures_quote(Market.KOSPI).futures_price == 300


class Test시세_운영시간:
    def test_야간은_금요일_세션의_토요일_새벽도_포함한다(self, mocker):
        opened = mocker.patch.object(scheduler.calendar, "is_open", return_value=True)
        assert scheduler._night_open(datetime(2026, 10, 3, 5))
        assert opened.call_args.args[0].isoformat() == "2026-10-02"
        assert not scheduler._night_open(datetime(2026, 10, 3, 12))

    @pytest.mark.parametrize("at", [datetime(2026, 9, 30, 22, 30), datetime(2026, 12, 1, 23, 30)])
    def test_미국_정규장은_서머타임을_따른다(self, mocker, at):
        mocker.patch.object(scheduler.calendar, "is_holiday", return_value=False)
        assert scheduler._us_cash_open(at)

    def test_주말_글로벌_시세는_쉬고_일요일_뉴욕_저녁에_재개한다(self):
        assert not scheduler._global_open(datetime(2026, 10, 4, 12))
        assert scheduler._global_open(datetime(2026, 10, 5, 7))
