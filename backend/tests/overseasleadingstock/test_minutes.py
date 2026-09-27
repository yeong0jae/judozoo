"""해외 1분봉 저장소 — 처음만 2거래일을 받고, 그다음은 새 봉만 받는다."""

from datetime import date, datetime, timedelta

import pytest

from backend.overseasleadingstock import minutes
from backend.platform.kis.overseas_chart import MinutePagesInterrupted, OverseasMinuteCandle

어제, 오늘 = date(2026, 9, 24), date(2026, 9, 25)   # 현지 영업일


def 봉(영업일: date, 한국시각: datetime, 종가: float = 100.0) -> OverseasMinuteCandle:
    return OverseasMinuteCandle(
        date_time=한국시각, trading_day=영업일, open=종가, high=종가, low=종가, close=종가,
        volume=1, trading_value=종가,
    )


@pytest.fixture
def KIS(monkeypatch):
    minutes.reset()
    상태 = {"지금": datetime(2026, 9, 25, 23, 0), "닫힘": False, "호출": [], "응답": [], "오류": None}
    monkeypatch.setattr(minutes, "now", lambda: 상태["지금"])
    monkeypatch.setattr(minutes, "_us_closed", lambda: 상태["닫힘"])

    def 받기(exchange, symbol, since=None):
        상태["호출"].append(since)
        if 상태["오류"]:
            raise 상태["오류"]
        return [c for c in 상태["응답"] if since is None or c.date_time >= since]

    monkeypatch.setattr(minutes.overseas_chart, "fetch_minute_candles", 받기)
    yield 상태
    minutes.reset()


def 처음_열기(KIS):
    KIS["응답"] = [봉(어제, datetime(2026, 9, 25, 4, 59)), 봉(오늘, datetime(2026, 9, 25, 22, 30))]
    return minutes.minute_candles("NAS", "AAPL")


class Test처음_열기:
    def test_2거래일치를_받아_시각순으로_준다(self, KIS):
        봉들 = 처음_열기(KIS)

        assert KIS["호출"] == [None]
        assert [c.trading_day for c in 봉들] == [어제, 오늘]

    def test_중간에_끊기면_보여만_주고_들고_있지_않는다(self, KIS):
        KIS["오류"] = MinutePagesInterrupted("끊김", [봉(오늘, datetime(2026, 9, 25, 22, 30))])

        assert len(minutes.minute_candles("NAS", "AAPL")) == 1
        KIS["오류"] = None
        minutes.minute_candles("NAS", "AAPL")

        assert KIS["호출"] == [None, None]    # 다음에도 처음처럼 받는다


class Test이어_받기:
    def test_60초_안이면_다시_부르지_않는다(self, KIS):
        처음_열기(KIS)
        KIS["지금"] += timedelta(seconds=30)

        minutes.minute_candles("NAS", "AAPL")

        assert len(KIS["호출"]) == 1

    def test_60초가_지나면_마지막_봉_2분_앞부터만_받는다(self, KIS):
        처음_열기(KIS)
        KIS["지금"] += timedelta(seconds=61)

        minutes.minute_candles("NAS", "AAPL")

        assert KIS["호출"][-1] == datetime(2026, 9, 25, 22, 28)

    def test_새_봉은_붙고_진행_중이던_봉은_덮어쓴다(self, KIS):
        처음_열기(KIS)
        KIS["지금"] += timedelta(seconds=61)
        KIS["응답"] = [봉(오늘, datetime(2026, 9, 25, 22, 30), 종가=105), 봉(오늘, datetime(2026, 9, 25, 22, 31), 종가=110)]

        봉들 = minutes.minute_candles("NAS", "AAPL")

        assert [c.close for c in 봉들 if c.trading_day == 오늘] == [105, 110]

    def test_새_영업일이_보이면_최신이던_날을_직전_거래일로_내린다(self, KIS):
        처음_열기(KIS)
        KIS["지금"] += timedelta(hours=18)
        다음날 = date(2026, 9, 26)
        KIS["응답"] = [봉(다음날, datetime(2026, 9, 26, 17, 0))]

        봉들 = minutes.minute_candles("NAS", "AAPL")

        assert sorted({c.trading_day for c in 봉들}) == [오늘, 다음날]   # 그제(어제) 봉은 빠진다

    def test_이어_받기가_실패하면_들고_있던_봉을_준다(self, KIS):
        처음_열기(KIS)
        KIS["지금"] += timedelta(seconds=61)
        KIS["오류"] = RuntimeError("KIS 오류")

        assert len(minutes.minute_candles("NAS", "AAPL")) == 2


class Test미국_장이_닫혀_있으면:
    def test_닫힌_뒤에_받은_값은_다시_받지_않는다(self, KIS):
        KIS["닫힘"] = True
        처음_열기(KIS)
        KIS["지금"] += timedelta(hours=10)

        minutes.minute_candles("NAS", "AAPL")

        assert len(KIS["호출"]) == 1

    def test_장중에_받은_값은_닫힌_뒤_한_번은_다시_받는다(self, KIS):
        """마감 직전 값은 마지막 봉이 덜 찼을 수 있다."""
        처음_열기(KIS)
        KIS["닫힘"] = True
        KIS["지금"] += timedelta(minutes=5)

        minutes.minute_candles("NAS", "AAPL")
        minutes.minute_candles("NAS", "AAPL")

        assert len(KIS["호출"]) == 2


class Test정리:
    def test_10분_동안_안_본_종목은_버린다(self, KIS):
        처음_열기(KIS)
        KIS["지금"] += timedelta(minutes=11)
        KIS["응답"] = [봉(오늘, datetime(2026, 9, 25, 22, 30))]
        minutes.minute_candles("NAS", "MSFT")        # 다른 종목을 열며 정리가 돈다

        minutes.minute_candles("NAS", "AAPL")

        assert KIS["호출"][-1] is None               # 처음처럼 다시 받는다
