"""시황 라우트 매칭 — 리터럴 경로가 {market}보다 먼저 잡히는지.

Spring은 리터럴 패턴을 우선하지만 FastAPI는 **선언 순서**로 매칭한다.
순서가 뒤집히면 `/market/nasdaq/candles`가 `{market}=nasdaq`으로 들어가 400이 난다.
"""

import pytest

from backend.market import application, calendar


@pytest.fixture(autouse=True)
def 외부호출_차단(monkeypatch):
    """라우트가 어디로 갔는지만 본다 — 실제 브로커는 부르지 않는다."""
    monkeypatch.setattr(application, "nasdaq_candles", lambda interval: [])
    monkeypatch.setattr(application, "nasdaq_quote", lambda: None)
    monkeypatch.setattr(application, "macro_candles", lambda target, interval: [])
    monkeypatch.setattr(application, "night_futures_quote", lambda: None)
    monkeypatch.setattr(application, "night_futures_candles", lambda interval, count: [])
    monkeypatch.setattr(application, "futures_quote", lambda market: None)
    monkeypatch.setattr(application, "daily_candles", lambda market, count: [])
    monkeypatch.setattr(calendar, "is_holiday", lambda region: False)


class Test리터럴_경로_우선:
    def test_나스닥_캔들은_시장_캔들로_새지_않는다(self, client):
        응답 = client.get("/api/market/nasdaq/candles", params={"interval": "1d"})

        assert 응답.status_code == 200
        assert 응답.json()["data"] == []

    def test_나스닥_시세도_마찬가지(self, client):
        assert client.get("/api/market/nasdaq/quote").status_code == 200

    def test_야간선물은_선물_시장_경로로_새지_않는다(self, client):
        응답 = client.get("/api/market/futures/night/quote")

        assert 응답.status_code == 200
        assert 응답.json()["data"] is None

    def test_야간선물_캔들도_마찬가지(self, client):
        응답 = client.get("/api/market/futures/night/candles", params={"interval": "1m"})

        assert 응답.status_code == 200

    def test_매크로_캔들은_시장_캔들로_새지_않는다(self, client):
        응답 = client.get("/api/market/macro/candles", params={"target": "WTI", "interval": "1d"})

        assert 응답.status_code == 200

    def test_휴장_상태는_시장_경로로_새지_않는다(self, client):
        응답 = client.get("/api/market/calendar/status", params={"region": "KR"})

        assert 응답.status_code == 200
        assert 응답.json()["data"] == {"isHoliday": False}


class Test시장_경로:
    def test_코스피_캔들은_정상_매칭된다(self, client):
        assert client.get("/api/market/KOSPI/candles", params={"interval": "1d"}).status_code == 200

    def test_정의에_없는_시장은_400(self, client):
        """Kotlin은 enum 변환 실패를 400 INVALID_PARAMETER로 준다."""
        응답 = client.get("/api/market/NIKKEI/candles", params={"interval": "1d"})

        assert 응답.status_code == 400
        assert 응답.json()["code"] == "INVALID_PARAMETER"

    def test_알_수_없는_지역도_400(self, client):
        응답 = client.get("/api/market/calendar/status", params={"region": "JP"})

        assert 응답.status_code == 400
