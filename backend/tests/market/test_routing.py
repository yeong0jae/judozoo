"""시황 라우트 매칭 — 리터럴 경로가 {market}보다 먼저 잡히는지.

Spring은 리터럴 패턴을 우선하지만 FastAPI는 **선언 순서**로 매칭한다.
순서가 뒤집히면 `/market/nasdaq/candles`가 `{market}=nasdaq`으로 들어가 400이 난다.
"""

from datetime import date

import pytest

from backend.market import application, calendar


@pytest.fixture(autouse=True)
def 외부호출_차단(monkeypatch):
    """라우트가 어디로 갔는지만 본다 — 실제 브로커는 부르지 않는다."""
    monkeypatch.setattr(application, "nasdaq_candles", lambda interval: [])
    monkeypatch.setattr(application, "nasdaq_quote", lambda: None)
    monkeypatch.setattr(application, "macro_candles", lambda target, interval: [])
    monkeypatch.setattr(application, "night_futures_quote", lambda: None)
    monkeypatch.setattr(application, "nasdaq_futures_quote", lambda: None)
    monkeypatch.setattr(application, "nasdaq_futures_candles", lambda interval: [])
    monkeypatch.setattr(application, "night_futures_candles", lambda interval, count: [])
    monkeypatch.setattr(application, "futures_quote", lambda market: None)
    monkeypatch.setattr(application, "daily_candles", lambda market, count: [])
    monkeypatch.setattr(application, "today_nets", lambda: [])
    monkeypatch.setattr(calendar, "is_holiday", lambda region: False)
    monkeypatch.setattr(calendar, "previous_open_day", lambda on: None)


class Test리터럴_경로_우선:
    def test_나스닥_캔들은_시장_캔들로_새지_않는다(self, 로그인_client):
        응답 = 로그인_client.get("/api/market/nasdaq/candles", params={"interval": "1d"})

        assert 응답.status_code == 200
        assert 응답.json()["data"] == []

    def test_나스닥_시세도_마찬가지(self, 로그인_client):
        assert 로그인_client.get("/api/market/nasdaq/quote").status_code == 200

    def test_야간선물은_선물_시장_경로로_새지_않는다(self, 로그인_client):
        응답 = 로그인_client.get("/api/market/futures/night/quote")

        assert 응답.status_code == 200
        assert 응답.json()["data"] is None

    def test_야간선물_캔들도_마찬가지(self, 로그인_client):
        응답 = 로그인_client.get("/api/market/futures/night/candles", params={"interval": "1m"})

        assert 응답.status_code == 200

    def test_나스닥_선물은_선물_시장_경로로_새지_않는다(self, 로그인_client):
        """`{market}`으로 새면 Market enum에 걸려 422가 난다 — 야간선물과 같은 함정이다."""
        응답 = 로그인_client.get("/api/market/futures/nasdaq/quote")

        assert 응답.status_code == 200
        assert 응답.json()["data"] is None

    def test_나스닥_선물_캔들도_마찬가지(self, 로그인_client):
        응답 = 로그인_client.get("/api/market/futures/nasdaq/candles", params={"interval": "1m"})

        assert 응답.status_code == 200

    def test_매크로_캔들은_시장_캔들로_새지_않는다(self, 로그인_client):
        응답 = 로그인_client.get("/api/market/macro/candles", params={"target": "WTI", "interval": "1d"})

        assert 응답.status_code == 200

    def test_오늘의_수급은_시장_수급_경로로_새지_않는다(self, 로그인_client):
        """`/investor/today`가 `{market}=investor`로 들어가면 Market enum에 걸려 422가 난다."""
        응답 = 로그인_client.get("/api/market/investor/today")

        assert 응답.status_code == 200
        assert 응답.json()["data"] == []

    def test_휴장_상태는_시장_경로로_새지_않는다(self, 로그인_client):
        응답 = 로그인_client.get("/api/market/calendar/status", params={"region": "KR"})

        assert 응답.status_code == 200
        assert 응답.json()["data"] == {"isHoliday": False, "previousOpenDay": None}


class Test시장_경로:
    def test_코스피_캔들은_정상_매칭된다(self, 로그인_client):
        assert 로그인_client.get("/api/market/KOSPI/candles", params={"interval": "1d"}).status_code == 200

    def test_정의에_없는_시장은_400(self, 로그인_client):
        """Kotlin은 enum 변환 실패를 400 INVALID_PARAMETER로 준다."""
        응답 = 로그인_client.get("/api/market/NIKKEI/candles", params={"interval": "1d"})

        assert 응답.status_code == 400
        assert 응답.json()["code"] == "INVALID_PARAMETER"

    def test_알_수_없는_지역도_400(self, 로그인_client):
        응답 = 로그인_client.get("/api/market/calendar/status", params={"region": "JP"})

        assert 응답.status_code == 400


class Test직전_개장일_응답:
    def test_국내는_직전_개장일을_싣는다(self, client, monkeypatch):
        monkeypatch.setattr(calendar, "previous_open_day", lambda on: date(2026, 9, 23))

        응답 = client.get("/api/market/calendar/status", params={"region": "KR"})

        assert 응답.json()["data"]["previousOpenDay"] == "2026-09-23"

    def test_해외는_싣지_않는다(self, client, monkeypatch):
        """개장일 목록(KIS)이 국내 것뿐이다 — 미국 날짜를 국내 달력으로 짚으면 틀린다."""
        monkeypatch.setattr(calendar, "previous_open_day", lambda on: date(2026, 9, 23))

        응답 = client.get("/api/market/calendar/status", params={"region": "US"})

        assert 응답.json()["data"]["previousOpenDay"] is None
