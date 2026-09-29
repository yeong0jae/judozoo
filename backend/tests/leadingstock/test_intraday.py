"""당일 분봉 저장소 — 한 번 채운 뒤 마지막 봉 이후만 받아 이어 붙인다."""

import threading
from concurrent.futures import ThreadPoolExecutor
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
    """종목별로 돌려줄 봉과 불린 기록(종목, since)을 쥔다. 시계는 `상태["지금"]`으로 움직인다."""
    intraday.reset()
    상태 = {"봉": {}, "호출": [], "실패": set(), "지금": datetime(2026, 9, 28, 10, 0), "오늘": 오늘}
    monkeypatch.setattr(intraday, "today", lambda: 상태["오늘"])
    monkeypatch.setattr(intraday, "now", lambda: 상태["지금"])

    def 받기(code, since=None):
        상태["호출"].append((code, since))
        if code in 상태["실패"]:
            raise RuntimeError("토스 오류")
        return [c for c in 상태["봉"].get(code, []) if since is None or c.date_time >= since]

    monkeypatch.setattr(intraday.toss_candles, "fetch_today_minute_candles", 받기)
    yield 상태
    intraday.reset()


def 분(c) -> list[int]:
    return [x.date_time.minute for x in c]


class Test감시_풀_갱신:
    def test_처음엔_오늘치를_통째로_받는다(self, 토스):
        토스["봉"]["005930"] = [봉(9, 0), 봉(9, 1)]

        intraday.sync(["005930"])

        assert 토스["호출"] == [("005930", None)]

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

        assert [c.close_price for c in intraday.ensure("005930", 토스["지금"])] == [100, 105, 110]

    def test_한_종목이_실패해도_나머지는_받고_실패_수를_돌려준다(self, 토스):
        토스["봉"]["000660"] = [봉(9, 0)]
        토스["실패"].add("005930")

        assert intraday.sync(["005930", "000660"]) == 1
        assert intraday.synced_since("000660", 토스["지금"])


class Test읽을_때_이어_받기:
    def test_기준_이후에_받은_값이면_부르지_않는다(self, 토스):
        토스["봉"]["005930"] = [봉(9, 0)]
        intraday.sync(["005930"])

        intraday.ensure("005930", 토스["지금"] - timedelta(seconds=30))

        assert len(토스["호출"]) == 1

    def test_낡았으면_새_봉만_받는다(self, 토스):
        토스["봉"]["005930"] = [봉(9, 0), 봉(9, 5)]
        intraday.sync(["005930"])
        토스["지금"] += timedelta(minutes=1)

        intraday.ensure("005930", 토스["지금"] - timedelta(seconds=30))

        assert 토스["호출"][-1] == ("005930", datetime(2026, 9, 28, 9, 3))

    def test_감시_풀_밖의_종목도_처음_읽을_때_통째로_받는다(self, 토스):
        토스["봉"]["035720"] = [봉(9, 0), 봉(9, 1)]

        assert 분(intraday.ensure("035720", 토스["지금"])) == [0, 1]
        assert 토스["호출"] == [("035720", None)]

    def test_받다가_실패하면_들고_있던_봉을_준다(self, 토스):
        토스["봉"]["005930"] = [봉(9, 0)]
        intraday.sync(["005930"])
        토스["실패"].add("005930")
        토스["지금"] += timedelta(minutes=1)

        assert 분(intraday.ensure("005930", 토스["지금"])) == [0]
        assert not intraday.synced_since("005930", 토스["지금"])

    def test_랭킹_코드와_맨_코드는_같은_종목이다(self, 토스):
        토스["봉"]["005930"] = [봉(9, 0)]
        intraday.sync(["005930_AL"])

        intraday.ensure("005930", 토스["지금"])

        assert len(토스["호출"]) == 1


class Test정리:
    def test_감시_풀에서_빠지고_한동안_안_읽힌_종목은_버린다(self, 토스):
        토스["봉"]["005930"] = [봉(9, 0)]
        intraday.sync(["005930"])
        토스["지금"] += timedelta(minutes=11)

        intraday.sync([])

        assert not intraday.synced_since("005930", datetime.min + timedelta(days=1))

    def test_상세_화면이_보고_있는_종목은_풀_밖이어도_남긴다(self, 토스):
        토스["봉"]["035720"] = [봉(9, 0)]
        intraday.ensure("035720", 토스["지금"])
        토스["지금"] += timedelta(minutes=5)

        intraday.sync([])

        assert intraday.synced_since("035720", datetime.min + timedelta(days=1))

    def test_상한을_넘으면_가장_오래_안_읽힌_종목부터_버린다(self, 토스, monkeypatch):
        monkeypatch.setattr(intraday, "_MAX_STOCKS", 1)
        intraday.ensure("000660", 토스["지금"])
        토스["지금"] += timedelta(seconds=10)

        intraday.ensure("035720", 토스["지금"])

        assert not intraday.synced_since("000660", datetime.min + timedelta(days=1))

    def test_날짜가_바뀌면_어제_봉을_버리고_새로_받는다(self, 토스):
        토스["봉"]["005930"] = [봉(9, 0)]
        intraday.sync(["005930"])
        다음날 = 오늘 + timedelta(days=1)
        토스["오늘"] = 다음날
        토스["봉"]["005930"] = [봉(9, 0, 날짜=다음날)]

        intraday.sync(["005930"])

        assert 토스["호출"][-1] == ("005930", None)


class Test마감_확정:
    def test_이번_회차에_받는_데_성공한_종목만_확정한다(self, 토스):
        토스["봉"]["005930"] = [봉(19, 59)]
        토스["봉"]["000660"] = [봉(19, 59)]
        intraday.sync(["005930", "000660"])
        토스["지금"] = datetime(2026, 9, 28, 20, 1)
        토스["실패"].add("000660")

        확정, 실패 = intraday.settle(["005930", "000660"])

        assert set(확정) == {"005930"}
        assert 실패 == 1


class Test동시_요청:
    def test_같은_종목을_동시에_열면_토스를_한_번만_부른다(self, 토스, monkeypatch):
        """감시 풀 밖의 종목을 여럿이 한꺼번에 연 상황."""
        토스["봉"]["000660"] = [봉(9, 0), 봉(9, 1)]
        받기, 풀림 = intraday.toss_candles.fetch_today_minute_candles, threading.Event()

        def 느린_받기(code, since=None):
            풀림.wait(1)
            return 받기(code, since)

        monkeypatch.setattr(intraday.toss_candles, "fetch_today_minute_candles", 느린_받기)
        with ThreadPoolExecutor(4) as pool:
            futures = [pool.submit(intraday.ensure, "000660", 토스["지금"]) for _ in range(4)]
            threading.Event().wait(0.1)
            풀림.set()
            결과 = [분(f.result()) for f in futures]

        assert len(토스["호출"]) == 1
        assert 결과 == [[0, 1]] * 4

    def test_막_받은_값이_있으면_뒤에_온_요청은_다시_받지_않는다(self, 토스):
        토스["봉"]["000660"] = [봉(9, 0)]
        intraday.ensure("000660", 토스["지금"])

        intraday.ensure("000660", 토스["지금"] - timedelta(seconds=30))

        assert len(토스["호출"]) == 1
