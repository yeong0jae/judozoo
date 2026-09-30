"""지수 캔들 — 보는 사람 수만큼 토스를 부르지 않는다(MARKET_INDICATOR_CHART 초당 5건)."""

from datetime import datetime

import pytest

from backend.market import application
from backend.platform.toss import market_indicator as toss_indicator
from backend.stock.domain import Market


def 봉(시: int, 분: int) -> toss_indicator.TossCandle:
    return toss_indicator.TossCandle(
        timestamp=datetime(2026, 9, 28, 시, 분), open=1.0, high=1.0, low=1.0, close=1.0, volume=1.0
    )


@pytest.fixture
def 토스(monkeypatch):
    호출 = []

    def 캔들(symbol, interval, count, before=None):
        호출.append((symbol, interval))
        return toss_indicator.CandlesPage(candles=[봉(9, 0), 봉(9, 1)], next_before=None)

    monkeypatch.setattr(application.toss_indicator, "fetch_candles", 캔들)
    return 호출


class Test지수_캔들_캐시:
    def test_1분봉을_30초_안에_다시_물으면_토스를_부르지_않는다(self, 토스):
        application.minute_candles_today(Market.KOSPI)
        application.minute_candles_today(Market.KOSPI)

        assert 토스 == [("KOSPI", "1m")]

    def test_지수가_다르면_따로_받는다(self, 토스):
        application.minute_candles_today(Market.KOSPI)
        application.minute_candles_today(Market.KOSDAQ)

        assert len(토스) == 2

    def test_일봉도_30초_안에는_다시_부르지_않는다(self, 토스):
        application.daily_candles(Market.KOSPI, 90)
        application.daily_candles(Market.KOSPI, 90)

        assert 토스 == [("KOSPI", "1d")]


@pytest.fixture
def 지수_보관소(통합_db, monkeypatch):
    from sqlalchemy import delete
    from backend.library.db import get_engine, get_session_factory
    from backend.market import minute_archive
    from backend.market.domain import IndexMinuteDay

    IndexMinuteDay.__table__.create(get_engine(), checkfirst=True)
    with get_session_factory()() as session:
        session.execute(delete(IndexMinuteDay))
        session.commit()
    monkeypatch.setattr(minute_archive, "get_session_factory", get_session_factory)
    return minute_archive


@pytest.mark.integration
class Test지난_거래일_지수_보관:
    def test_캐시를_비워도_직전일은_DB에서_복원하고_페이지를_덜_받는다(self, 지수_보관소, monkeypatch):
        from datetime import date
        from dataclasses import replace
        from backend.library.cache import clear_all

        오늘 = replace(봉(9, 0), timestamp=datetime(2026, 9, 29, 9))
        어제 = [봉(9, 0), 봉(9, 1)]
        이전 = replace(봉(9, 0), timestamp=datetime(2026, 9, 25, 15))
        monkeypatch.setattr(application, "today", lambda: date(2026, 9, 29))
        호출 = []
        페이지 = {
            None: toss_indicator.CandlesPage([오늘, 어제[1]], "older"),
            "older": toss_indicator.CandlesPage([어제[0], 이전], "more"),
        }

        def 조회(symbol, interval, count, before=None):
            호출.append(before)
            return 페이지[before]

        monkeypatch.setattr(application.toss_indicator, "fetch_candles", 조회)
        assert application.minute_candles_today(Market.KOSPI) == 어제 + [오늘]
        assert 지수_보관소.get(Market.KOSPI, date(2026, 9, 28)) == 어제
        assert 지수_보관소.get(Market.KOSPI, date(2026, 9, 29)) is None
        assert 지수_보관소.get(Market.KOSPI, date(2026, 9, 25)) is None
        clear_all()
        호출.clear()
        assert application.minute_candles_today(Market.KOSPI) == 어제 + [오늘]
        assert 호출 == [None]
        assert 지수_보관소.get(Market.KOSDAQ, date(2026, 9, 28)) is None

    def test_다음_페이지_실패로_잘린_날은_보관하지_않는다(self, 지수_보관소, monkeypatch):
        from datetime import date
        monkeypatch.setattr(application, "today", lambda: date(2026, 9, 29))
        monkeypatch.setattr(application.toss_indicator, "fetch_candles",
                            lambda symbol, interval, count, before=None:
                            toss_indicator.CandlesPage([봉(15, 0)], "older") if before is None
                            else toss_indicator.CandlesPage([], None))
        assert application.minute_candles_today(Market.KOSPI) == [봉(15, 0)]
        assert 지수_보관소.get(Market.KOSPI, date(2026, 9, 28)) is None

    def test_휴일에도_최근_두_거래일을_보관하고_중복_저장할_수_있다(self, 지수_보관소, monkeypatch):
        from datetime import date
        from dataclasses import replace
        monkeypatch.setattr(application, "today", lambda: date(2026, 10, 3))
        최근 = replace(봉(9, 0), timestamp=datetime(2026, 10, 2, 9))
        직전 = replace(봉(9, 0), timestamp=datetime(2026, 10, 1, 9))
        monkeypatch.setattr(application.toss_indicator, "fetch_candles",
                            lambda *args: toss_indicator.CandlesPage([최근, 직전], None))
        assert application.minute_candles_today(Market.KOSPI) == [직전, 최근]
        지수_보관소.put(Market.KOSPI, date(2026, 10, 2), [최근])
        assert 지수_보관소.get(Market.KOSPI, date(2026, 10, 2)) == [최근]
        assert 지수_보관소.get(Market.KOSPI, date(2026, 10, 1)) == [직전]
