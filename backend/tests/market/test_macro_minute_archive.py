"""매크로 1분봉은 지난 미국 거래 세션을 보관하고 진행 중인 세션만 갱신한다."""

from datetime import date, datetime

import pytest
from sqlalchemy import delete

from backend.library.cache import clear_all
from backend.library.db import get_engine, get_session_factory
from backend.library.time import KST
from backend.market import application, calendar, yahoo_minute_archive
from backend.market.domain import YahooMinuteDay
from backend.platform.yahoo.client import YahooBar


def 미국시각_분봉(day: date, clock: str) -> YahooBar:
    at = datetime.fromisoformat(f"{day}T{clock}").replace(tzinfo=calendar.Region.US.zone).astimezone(KST)
    return YahooBar(at.date().isoformat(), at.time().isoformat(), 1.0, 2.0, 0.5, 1.5, 10.0)


@pytest.fixture
def 매크로_보관소(통합_db, mocker):
    YahooMinuteDay.__table__.create(get_engine(), checkfirst=True)
    with get_session_factory()() as session:
        session.execute(delete(YahooMinuteDay))
        session.commit()
    mocker.patch.object(yahoo_minute_archive, "get_session_factory", get_session_factory)
    yield yahoo_minute_archive
    clear_all()


@pytest.mark.integration
class Test매크로_지난_거래_세션:
    @pytest.mark.parametrize(
        ("target", "symbol", "previous_times", "current_times"),
        [
            ("USD_KRW", "KRW=X", ((date(2026, 9, 28), "17:00:00"), (date(2026, 9, 29), "16:59:00")),
             ((date(2026, 9, 29), "17:00:00"), (date(2026, 9, 30), "14:00:00"))),
            ("WTI", "CL=F", ((date(2026, 9, 28), "18:00:00"), (date(2026, 9, 29), "16:59:00")),
             ((date(2026, 9, 29), "18:00:00"), (date(2026, 9, 30), "14:00:00"))),
            ("VIX", "^VIX", ((date(2026, 9, 29), "09:30:00"), (date(2026, 9, 29), "15:59:00")),
             ((date(2026, 9, 30), "09:30:00"), (date(2026, 9, 30), "14:00:00"))),
            ("US10Y", "^TNX", ((date(2026, 9, 29), "08:20:00"), (date(2026, 9, 29), "15:00:00")),
             ((date(2026, 9, 30), "08:20:00"), (date(2026, 9, 30), "14:00:00"))),
        ],
    )
    def test_지난_세션은_저장하고_최신_세션만_다시_조회한다(
        self, 매크로_보관소, mocker, target, symbol, previous_times, current_times,
    ):
        mocker.patch.object(application, "now", return_value=datetime(2026, 10, 1, 3))
        previous = [미국시각_분봉(*at) for at in previous_times]
        current = [미국시각_분봉(*at) for at in current_times]
        fetch = mocker.patch.object(
            application.yahoo, "fetch_candles", side_effect=lambda _, __, window: current if window == "1d" else previous + current,
        )

        assert application.macro_candles(target, "1m") == previous + current
        assert 매크로_보관소.recent(symbol, date(2026, 9, 30)) == {date(2026, 9, 29): previous}
        assert application.macro_candles(target, "1m") == previous + current
        assert fetch.call_count == 1

        clear_all()
        assert application.macro_candles(target, "1m") == previous + current
        assert [call.args for call in fetch.call_args_list] == [(symbol, "1m", "2d"), (symbol, "1m", "1d")]
        assert date(2026, 9, 30) not in 매크로_보관소.recent(symbol, date(2026, 9, 30))

    @pytest.mark.parametrize(
        ("target", "symbol", "times"),
        [
            ("USD_KRW", "KRW=X", ((date(2026, 9, 29), "10:00:00"), (date(2026, 9, 29), "16:59:00"))),
            ("WTI", "CL=F", ((date(2026, 9, 29), "10:00:00"), (date(2026, 9, 29), "16:59:00"))),
            ("VIX", "^VIX", ((date(2026, 9, 29), "11:00:00"), (date(2026, 9, 29), "15:59:00"))),
            ("US10Y", "^TNX", ((date(2026, 9, 29), "11:00:00"), (date(2026, 9, 29), "15:00:00"))),
        ],
    )
    def test_앞부분이_잘린_세션은_보관하지_않는다(self, 매크로_보관소, mocker, target, symbol, times):
        mocker.patch.object(application, "now", return_value=datetime(2026, 10, 1, 3))
        mocker.patch.object(application.yahoo, "fetch_candles", return_value=[미국시각_분봉(*at) for at in times])

        application.macro_candles(target, "1m")

        assert 매크로_보관소.recent(symbol, date(2026, 9, 30)) == {}
