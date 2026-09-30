"""지수선물 분봉: 진행 중인 세션은 메모리, 완성된 세션은 DB에 둔다."""

from datetime import date, datetime

import pytest
from sqlalchemy import delete

from backend.library.cache import clear_all
from backend.library.db import get_engine, get_session_factory
from backend.market import application, futures_minute_archive
from backend.market.domain import FuturesMinuteDay
from backend.platform.kis.futures import FuturesBar, NearMonth
from backend.stock.domain import Market


def 봉(day: date, clock: str) -> FuturesBar:
    return FuturesBar(day.isoformat(), clock, 1.0, 2.0, 0.5, 1.5, 10.0)


@pytest.fixture
def 선물_보관소(통합_db, mocker):
    FuturesMinuteDay.__table__.create(get_engine(), checkfirst=True)
    with get_session_factory()() as session:
        session.execute(delete(FuturesMinuteDay))
        session.commit()
    mocker.patch.object(futures_minute_archive, "get_session_factory", get_session_factory)
    mocker.patch.object(application.calendar, "is_open", return_value=True)
    mocker.patch.object(application.kis_futures, "fetch_near_month", return_value=NearMonth("CONTRACT", "F 202612", 50))
    yield futures_minute_archive
    clear_all()


@pytest.mark.integration
class Test지난_거래일_선물_분봉:
    @pytest.mark.parametrize("market", [Market.KOSPI, Market.KOSDAQ])
    def test_지난_거래일은_DB에서_재사용하고_오늘은_다시_조회한다(self, 선물_보관소, mocker, market):
        current = date(2026, 9, 30)
        previous = date(2026, 9, 29)
        mocker.patch.object(application, "today", return_value=current)
        mocker.patch.object(application, "now", return_value=datetime(2026, 9, 30, 13))
        호출 = []

        def 조회(iscd, day, hour, market_code="F"):
            호출.append(day)
            return [봉(day, "08:45:00"), 봉(day, "09:00:00")]

        mocker.patch.object(application.kis_futures, "fetch_minute", side_effect=조회)

        assert application.futures_candles(market, "1m", 90) == [
            봉(previous, "08:45:00"), 봉(previous, "09:00:00"),
            봉(current, "08:45:00"), 봉(current, "09:00:00"),
        ]
        assert 선물_보관소.get(market, "CONTRACT", False, previous) is not None
        assert 선물_보관소.get(market, "CONTRACT", False, current) is None
        assert 선물_보관소.get(market, "OTHER", False, previous) is None

        clear_all()
        호출.clear()
        application.futures_candles(market, "1m", 90)
        assert 호출 == [current]

    def test_전날_야간_세션은_DB에서_복원한다(self, 선물_보관소, mocker):
        current = date(2026, 9, 30)
        previous = date(2026, 9, 29)
        mocker.patch.object(application, "today", return_value=current)
        mocker.patch.object(application, "now", return_value=datetime(2026, 9, 30, 14))
        외부 = mocker.patch.object(application.kis_futures, "fetch_minute", return_value=[
            봉(previous, "18:00:00"), 봉(current, "05:59:00"),
        ])

        assert application.night_futures_candles("1m", 90) == [
            봉(previous, "18:00:00"), 봉(current, "05:59:00"),
        ]
        assert 선물_보관소.get(Market.KOSPI, "CONTRACT", True, previous) is not None
        assert 선물_보관소.get(Market.KOSPI, "CONTRACT", False, previous) is None

        clear_all()
        외부.reset_mock()
        application.night_futures_candles("1m", 90)
        외부.assert_not_called()

    def test_진행_중인_야간_세션은_DB에_저장하지_않는다(self, 선물_보관소, mocker):
        current = date(2026, 9, 30)
        mocker.patch.object(application, "today", return_value=current)
        mocker.patch.object(application, "now", return_value=datetime(2026, 9, 30, 19))
        외부 = mocker.patch.object(application.kis_futures, "fetch_minute", return_value=[
            봉(current, "18:00:00"), 봉(current, "19:00:00"),
        ])

        application.night_futures_candles("1m", 90)
        assert 선물_보관소.get(Market.KOSPI, "CONTRACT", True, current) is None

        clear_all()
        application.night_futures_candles("1m", 90)
        assert 외부.call_count == 2

    def test_자정_이후에도_전날_시작한_야간_세션으로_묶는다(self, 선물_보관소, mocker):
        start = date(2026, 9, 29)
        after_midnight = date(2026, 9, 30)
        mocker.patch.object(application, "now", return_value=datetime(2026, 9, 30, 2))
        외부 = mocker.patch.object(application.kis_futures, "fetch_minute", return_value=[
            봉(start, "18:00:00"), 봉(after_midnight, "02:00:00"),
        ])

        결과 = application.night_futures_candles("1m", 90)

        assert 결과 == [봉(start, "18:00:00"), 봉(after_midnight, "02:00:00")]
        assert 외부.call_args.args[1] == start
        assert 선물_보관소.get(Market.KOSPI, "CONTRACT", True, start) is None

    def test_잘린_지난날_페이지는_보관하지_않는다(self, 선물_보관소, mocker):
        previous = date(2026, 9, 29)
        mocker.patch.object(application, "today", return_value=date(2026, 9, 30))
        mocker.patch.object(application, "now", return_value=datetime(2026, 9, 30, 13))
        mocker.patch.object(application.kis_futures, "fetch_minute", side_effect=lambda iscd, day, hour: (
            [봉(day, "09:30:00")] * 102 if day == previous and hour.hour == 15 else []
        ))

        application.futures_candles(Market.KOSPI, "1m", 90)

        assert 선물_보관소.get(Market.KOSPI, "CONTRACT", False, previous) is None
