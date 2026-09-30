"""해외 1분봉 저장소 — 처음만 2거래일을 받고, 그다음은 새 봉만 받는다."""

import threading
from concurrent.futures import ThreadPoolExecutor
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
    # 보관소는 메모리 흉내로 — DB 층은 test_minute_archive_db가 본다
    상태["보관"] = {}
    monkeypatch.setattr(
        minutes.minute_archive, "recent",
        lambda ex, sym, limit: sorted(((d, b) for (e, s, d), b in 상태["보관"].items() if (e, s) == (ex, sym)), reverse=True)[:limit],
    )
    monkeypatch.setattr(minutes.minute_archive, "put", lambda ex, sym, d, b: 상태["보관"].__setitem__((ex, sym, d), b))
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
    def test_30초_안이면_다시_부르지_않는다(self, KIS):
        처음_열기(KIS)
        KIS["지금"] += timedelta(seconds=20)

        minutes.minute_candles("NAS", "AAPL")

        assert len(KIS["호출"]) == 1

    def test_30초가_지나면_마지막_봉_2분_앞부터만_받는다(self, KIS):
        처음_열기(KIS)
        KIS["지금"] += timedelta(seconds=31)

        minutes.minute_candles("NAS", "AAPL")

        assert KIS["호출"][-1] == datetime(2026, 9, 25, 22, 28)

    def test_새_봉은_붙고_진행_중이던_봉은_덮어쓴다(self, KIS):
        처음_열기(KIS)
        KIS["지금"] += timedelta(seconds=31)
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
        KIS["지금"] += timedelta(seconds=31)
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
    def test_10분_넘게_안_봐도_당일_봉을_이어_받는다(self, KIS):
        처음_열기(KIS)
        KIS["지금"] += timedelta(minutes=11)
        KIS["응답"] = [봉(오늘, datetime(2026, 9, 25, 22, 30))]
        minutes.minute_candles("NAS", "MSFT")        # 다른 종목을 열며 정리가 돈다

        minutes.minute_candles("NAS", "AAPL")

        assert KIS["호출"][-1] == datetime(2026, 9, 25, 22, 28)


class Test끝난_날_보관:
    def test_처음_열어_2거래일치를_받으면_직전_거래일을_넘긴다(self, KIS):
        처음_열기(KIS)

        assert list(KIS["보관"]) == [("NAS", "AAPL", 어제)]

    def test_날이_넘어가면_최신이던_날을_넘긴다(self, KIS):
        처음_열기(KIS)
        KIS["지금"] += timedelta(hours=18)
        다음날 = date(2026, 9, 26)
        KIS["응답"] = [봉(오늘, datetime(2026, 9, 26, 8, 59)), 봉(다음날, datetime(2026, 9, 26, 17, 0))]

        minutes.minute_candles("NAS", "AAPL")

        assert [c.date_time.hour for c in KIS["보관"][("NAS", "AAPL", 오늘)]] == [22, 8]   # 마지막 봉까지 담겨 넘어간다

    def test_마감_확정은_들고_있는_종목의_최신_거래일을_한_번_더_받아_넘긴다(self, KIS):
        처음_열기(KIS)
        KIS["응답"].append(봉(오늘, datetime(2026, 9, 26, 8, 59), 종가=120))

        assert minutes.settle() == 0

        assert KIS["호출"][-1] == datetime(2026, 9, 25, 22, 28)          # 마지막 봉 2분 앞부터
        assert [c.close for c in KIS["보관"][("NAS", "AAPL", 오늘)]] == [100, 120]

    def test_마감_확정에_실패한_종목은_세고_넘기지_않는다(self, KIS):
        처음_열기(KIS)
        KIS["오류"] = RuntimeError("KIS 오류")

        assert minutes.settle() == 1
        assert ("NAS", "AAPL", 오늘) not in KIS["보관"]


class Test보관소에서_열기:
    def test_직전_거래일이_있으면_그_뒤_봉만_받는다(self, KIS):
        KIS["보관"][("NAS", "AAPL", 어제)] = [봉(어제, datetime(2026, 9, 25, 4, 59))]
        KIS["응답"] = [봉(어제, datetime(2026, 9, 25, 4, 59)), 봉(오늘, datetime(2026, 9, 25, 22, 30))]

        봉들 = minutes.minute_candles("NAS", "AAPL")

        assert KIS["호출"] == [datetime(2026, 9, 25, 5, 0)]              # 보관한 마지막 봉 1분 뒤부터
        assert [c.trading_day for c in 봉들] == [어제, 오늘]

    def test_새_봉이_없으면_보관한_두_날을_보여준다(self, KIS):
        """장이 열리기 전 — 오늘 봉이 아직 없다."""
        그제 = date(2026, 9, 23)
        KIS["보관"][("NAS", "AAPL", 그제)] = [봉(그제, datetime(2026, 9, 24, 4, 59))]
        KIS["보관"][("NAS", "AAPL", 어제)] = [봉(어제, datetime(2026, 9, 25, 4, 59))]
        KIS["응답"] = []

        봉들 = minutes.minute_candles("NAS", "AAPL")

        assert [c.trading_day for c in 봉들] == [그제, 어제]

    def test_보관한_날이_하나뿐이고_새_봉도_없으면_2거래일치를_받는다(self, KIS):
        KIS["보관"][("NAS", "AAPL", 어제)] = [봉(어제, datetime(2026, 9, 25, 4, 59))]
        KIS["응답"] = []

        minutes.minute_candles("NAS", "AAPL")

        assert KIS["호출"] == [datetime(2026, 9, 25, 5, 0), None]

    def test_보관한_날이_너무_오래됐으면_2거래일치를_받는다(self, KIS):
        """그 뒤를 이어 받으면 2거래일치보다 더 받게 된다."""
        옛날 = date(2026, 9, 18)
        KIS["보관"][("NAS", "AAPL", 옛날)] = [봉(옛날, datetime(2026, 9, 19, 4, 59))]
        처음_열기(KIS)

        assert KIS["호출"] == [None]


class Test동시_요청:
    def test_같은_종목을_동시에_처음_열면_KIS를_한_번만_부른다(self, KIS, monkeypatch):
        """재시작 직후 인기 종목을 여럿이 한꺼번에 연 상황 — 각자 12페이지씩 받으면 한도에 걸린다."""
        KIS["응답"] = [봉(어제, datetime(2026, 9, 25, 4, 59)), 봉(오늘, datetime(2026, 9, 25, 22, 30))]
        받기, 풀림 = minutes.overseas_chart.fetch_minute_candles, threading.Event()

        def 느린_받기(exchange, symbol, since=None):
            풀림.wait(1)
            return 받기(exchange, symbol, since)

        monkeypatch.setattr(minutes.overseas_chart, "fetch_minute_candles", 느린_받기)
        with ThreadPoolExecutor(4) as pool:
            futures = [pool.submit(minutes.minute_candles, "NAS", "NVDA") for _ in range(4)]
            threading.Event().wait(0.1)
            풀림.set()
            결과 = [len(f.result()) for f in futures]

        assert KIS["호출"] == [None]
        assert 결과 == [2] * 4

    def test_30초가_지나_동시에_물어도_이어_받기는_한_번이다(self, KIS, monkeypatch):
        처음_열기(KIS)
        KIS["지금"] += timedelta(seconds=31)
        받기, 풀림 = minutes.overseas_chart.fetch_minute_candles, threading.Event()

        def 느린_받기(exchange, symbol, since=None):
            풀림.wait(1)
            return 받기(exchange, symbol, since)

        monkeypatch.setattr(minutes.overseas_chart, "fetch_minute_candles", 느린_받기)
        with ThreadPoolExecutor(4) as pool:
            futures = [pool.submit(minutes.minute_candles, "NAS", "AAPL") for _ in range(4)]
            threading.Event().wait(0.1)
            풀림.set()
            [f.result() for f in futures]

        assert len(KIS["호출"]) == 2   # 처음 열기 + 이어 받기 한 번


class Test거래일_분리:
    def test_한국_자정이_지나도_같은_미국_거래일_봉은_이어_받는다(self, KIS):
        처음_열기(KIS)
        KIS["지금"] = datetime(2026, 9, 26, 0, 1)
        KIS["응답"] = [봉(오늘, KIS["지금"], 종가=110)]

        결과 = minutes.minute_candles("NAS", "AAPL")

        assert [c.close for c in 결과 if c.trading_day == 오늘] == [100, 110]
        assert ("NAS", "AAPL", 오늘) not in KIS["보관"]

    def test_거래일_전환_중_조회가_끊기면_이전_날을_확정하지_않고_재시도한다(self, KIS):
        처음_열기(KIS)
        KIS["지금"] += timedelta(days=1)
        다음날 = 오늘 + timedelta(days=1)
        새봉 = 봉(다음날, datetime(2026, 9, 26, 22, 30))
        KIS["오류"] = MinutePagesInterrupted("끊김", [새봉])
        minutes.minute_candles("NAS", "AAPL")
        assert ("NAS", "AAPL", 오늘) not in KIS["보관"]

        KIS["오류"] = None
        KIS["응답"] = [봉(오늘, datetime(2026, 9, 26, 8, 59)), 새봉]
        결과 = minutes.minute_candles("NAS", "AAPL")
        assert KIS["호출"][-1] == datetime(2026, 9, 25, 22, 28)
        assert [c.trading_day for c in 결과] == [오늘, 오늘, 다음날]
        assert len(KIS["보관"][("NAS", "AAPL", 오늘)]) == 2

    def test_며칠_건너뛰면_실제_직전_거래일을_보관소에서_합친다(self, KIS):
        처음_열기(KIS)
        KIS["지금"] += timedelta(days=3)
        직전, 최신 = date(2026, 9, 27), date(2026, 9, 28)
        KIS["응답"] = [봉(직전, datetime(2026, 9, 27, 23)), 봉(최신, datetime(2026, 9, 28, 23))]
        결과 = minutes.minute_candles("NAS", "AAPL")
        assert [c.trading_day for c in 결과] == [직전, 최신]
        assert ("NAS", "AAPL", 직전) in KIS["보관"]

    def test_오래_안_본_종목은_최근_두_거래일로_다시_받는다(self, KIS):
        처음_열기(KIS)
        KIS["지금"] += timedelta(days=10)
        KIS["응답"] = [봉(date(2026, 10, 2), datetime(2026, 10, 2, 23)),
                       봉(date(2026, 10, 5), datetime(2026, 10, 5, 23))]
        결과 = minutes.minute_candles("NAS", "AAPL")
        assert KIS["호출"][-1] is None
        assert [c.trading_day for c in 결과] == [date(2026, 10, 2), date(2026, 10, 5)]

    def test_상한을_넘으면_오래_안_본_종목은_보관소에서_복원한다(self, KIS, monkeypatch):
        monkeypatch.setattr(minutes, "_MAX_STOCKS", 1)
        처음_열기(KIS)
        KIS["지금"] += timedelta(seconds=1)
        minutes.minute_candles("NAS", "MSFT")
        minutes.minute_candles("NAS", "AAPL")
        assert KIS["호출"][-1] == datetime(2026, 9, 25, 5, 0)

    def test_닫힌_뒤_받았어도_미국_날짜가_달라지면_갱신한다(self, KIS):
        KIS["닫힘"] = True
        처음_열기(KIS)
        KIS["지금"] += timedelta(days=3)
        minutes.minute_candles("NAS", "AAPL")
        assert len(KIS["호출"]) == 2
