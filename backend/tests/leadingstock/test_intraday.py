"""당일 분봉 저장소 — 한 번 채운 뒤 마지막 봉 이후만 받아 이어 붙인다."""

from datetime import date, datetime, timedelta

import pytest

from backend.leadingstock import intraday
from backend.leadingstock.domain import MinuteCandle

오늘 = date(2026, 9, 28)


def 봉(시: int, 분: int, 종가: int = 100, 날짜: date = 오늘) -> MinuteCandle:
    return MinuteCandle(
        date_time=datetime(날짜.year, 날짜.month, 날짜.day, 시, 분), open_price=종가, high_price=종가,
        low_price=종가, close_price=종가, volume=1, trading_value=종가,
    )


@pytest.fixture
def 토스(monkeypatch):
    """종목별로 돌려줄 봉과, 불린 기록(종목, since)을 쥔다."""
    intraday.reset()
    monkeypatch.setattr(intraday, "today", lambda: 오늘)
    상태 = {"봉": {}, "호출": [], "실패": set()}

    def 받기(code, since=None):
        상태["호출"].append((code, since))
        if code in 상태["실패"]:
            raise RuntimeError("토스 오류")
        return [c for c in 상태["봉"].get(code, []) if since is None or c.date_time >= since]

    monkeypatch.setattr(intraday.toss_candles, "fetch_today_minute_candles", 받기)
    yield 상태
    intraday.reset()


class Test처음_채우기:
    def test_처음엔_오늘치를_통째로_받는다(self, 토스):
        토스["봉"]["005930"] = [봉(9, 0), 봉(9, 1)]

        intraday.sync(["005930"])

        assert 토스["호출"] == [("005930", None)]
        assert [c.date_time.minute for c in intraday.get("005930")] == [0, 1]


class Test이어_받기:
    def test_두_번째부터는_마지막_봉_조금_앞부터만_받는다(self, 토스):
        토스["봉"]["005930"] = [봉(9, 0), 봉(9, 1), 봉(9, 2)]
        intraday.sync(["005930"])

        intraday.sync(["005930"])

        assert 토스["호출"][1] == ("005930", datetime(2026, 9, 28, 9, 0))   # 마지막 09:02에서 2분 앞

    def test_새_봉은_뒤에_붙고_진행_중이던_봉은_덮어쓴다(self, 토스):
        토스["봉"]["005930"] = [봉(9, 0), 봉(9, 1, 종가=100)]
        intraday.sync(["005930"])
        토스["봉"]["005930"] = [봉(9, 0), 봉(9, 1, 종가=105), 봉(9, 2, 종가=110)]

        intraday.sync(["005930"])

        assert [c.close_price for c in intraday.get("005930")] == [100, 105, 110]

    def test_랭킹_코드와_맨_코드는_같은_종목이다(self, 토스):
        토스["봉"]["005930"] = [봉(9, 0)]

        intraday.sync(["005930_AL"])

        assert intraday.get("005930") is not None


class Test실패와_끊김:
    def test_한_종목이_실패해도_나머지는_받고_실패_수를_돌려준다(self, 토스):
        토스["봉"]["000660"] = [봉(9, 0)]
        토스["실패"].add("005930")

        assert intraday.sync(["005930", "000660"]) == 1
        assert intraday.get("000660") is not None

    def test_실패한_종목은_갖고_있던_봉을_그대로_둔다(self, 토스):
        토스["봉"]["005930"] = [봉(9, 0)]
        intraday.sync(["005930"])
        토스["실패"].add("005930")

        intraday.sync(["005930"])

        assert [c.date_time.minute for c in intraday.get("005930")] == [0]

    def test_갱신이_끊긴_지_오래면_내주지_않는다(self, 토스, monkeypatch):
        """읽는 쪽이 낡은 봉을 최신으로 믿지 않게 — 그때는 직접 받아 간다."""
        토스["봉"]["005930"] = [봉(9, 0)]
        intraday.sync(["005930"])
        지금 = intraday.time.monotonic()
        monkeypatch.setattr(intraday.time, "monotonic", lambda: 지금 + intraday.STALE_SECONDS + 1)

        assert intraday.get("005930") is None

    def test_받은_적_없는_종목은_없다고_답한다(self, 토스):
        assert intraday.get("005930") is None


class Test목록과_날짜:
    def test_감시_풀에서_빠진_종목은_버린다(self, 토스):
        토스["봉"]["005930"] = [봉(9, 0)]
        intraday.sync(["005930"])

        intraday.sync([])

        assert intraday.get("005930") is None

    def test_날짜가_바뀌면_어제_봉을_버리고_새로_받는다(self, 토스, monkeypatch):
        토스["봉"]["005930"] = [봉(9, 0)]
        intraday.sync(["005930"])
        다음날 = 오늘 + timedelta(days=1)
        monkeypatch.setattr(intraday, "today", lambda: 다음날)
        토스["봉"]["005930"] = [봉(9, 0, 날짜=다음날)]

        intraday.sync(["005930"])

        assert 토스["호출"][-1] == ("005930", None)
        assert [c.date_time.date() for c in intraday.get("005930")] == [다음날]
