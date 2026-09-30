"""나스닥 현물 1분봉은 미국 거래일 기준으로 지난 날만 DB에 확정한다."""

from datetime import date, datetime

import pytest
from sqlalchemy import delete

from backend.library.cache import clear_all
from backend.library.db import get_engine, get_session_factory
from backend.market import application, yahoo_minute_archive
from backend.market.domain import YahooMinuteDay
from backend.platform.yahoo.client import YahooBar


def 봉(day: date, clock: str) -> YahooBar:
    return YahooBar(day.isoformat(), clock, 1.0, 2.0, 0.5, 1.5, 10.0)


@pytest.fixture
def 나스닥_보관소(통합_db, mocker):
    YahooMinuteDay.__table__.create(get_engine(), checkfirst=True)
    with get_session_factory()() as session:
        session.execute(delete(YahooMinuteDay))
        session.commit()
    mocker.patch.object(yahoo_minute_archive, "get_session_factory", get_session_factory)
    yield yahoo_minute_archive
    clear_all()


@pytest.mark.integration
class Test나스닥_지난_거래일_분봉:
    def test_한국시간_자정을_넘어도_한_미국_거래일로_보관하고_재사용한다(self, 나스닥_보관소, mocker):
        mocker.patch.object(application, "now", return_value=datetime(2026, 10, 1, 3))
        previous = [봉(date(2026, 9, 29), "22:30:00"), 봉(date(2026, 9, 30), "04:59:00")]
        current = [봉(date(2026, 9, 30), "22:30:00"), 봉(date(2026, 10, 1), "03:00:00")]
        windows = []

        def 조회(symbol, interval, window):
            windows.append(window)
            return current if window == "1d" else previous + current

        mocker.patch.object(application.yahoo, "fetch_candles", side_effect=조회)

        assert application.nasdaq_candles("1m") == previous + current
        assert 나스닥_보관소.recent("^IXIC", date(2026, 9, 30)) == {
            date(2026, 9, 29): previous,
        }

        clear_all()
        assert application.nasdaq_candles("1m") == previous + current
        assert windows == ["2d", "1d"]
        assert date(2026, 9, 30) not in 나스닥_보관소.recent("^IXIC", date(2026, 9, 30))

    def test_잘린_지난날은_DB에_확정하지_않는다(self, 나스닥_보관소, mocker):
        mocker.patch.object(application, "now", return_value=datetime(2026, 10, 1, 3))
        mocker.patch.object(application.yahoo, "fetch_candles", return_value=[
            봉(date(2026, 9, 30), "00:00:00"),  # 미국 동부 11:00 — 장 시작이 잘렸다
            봉(date(2026, 9, 30), "04:59:00"),
        ])

        application.nasdaq_candles("1m")

        assert 나스닥_보관소.recent("^IXIC", date(2026, 9, 30)) == {}
