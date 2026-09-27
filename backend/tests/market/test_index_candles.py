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
