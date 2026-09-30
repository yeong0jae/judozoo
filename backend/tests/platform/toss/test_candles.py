"""토스 당일 1분봉 — 키움과 같은 봉이 되도록 시각을 당기고 빈 봉을 버린다."""

from datetime import date, datetime

import httpx
import pytest
import respx

from backend.platform.toss import candles, client

BASE = "https://openapi.tossinvest.com"
TOKEN_URL = f"{BASE}/oauth2/token"
CANDLES_URL = f"{BASE}/api/v1/candles"
오늘 = date(2026, 9, 28)


@pytest.fixture(autouse=True)
def 토스_초기화(monkeypatch):
    client.reset()
    candles.reset()
    monkeypatch.setattr(candles, "today", lambda: 오늘)
    monkeypatch.setattr(candles, "now", lambda: datetime(2026, 9, 28, 10, 0))
    yield
    client.reset()
    candles.reset()


@pytest.fixture
def 토큰_발급(respx_mock):
    respx_mock.post(TOKEN_URL).mock(
        return_value=httpx.Response(200, json={"access_token": "tok", "expires_in": 86400})
    )


def 봉(끝: str, 종가: int = 100, 거래량: int = 10) -> dict:
    """토스 모양 그대로 — 시각은 봉이 끝나는 시각이다."""
    return {
        "timestamp": f"{끝}+09:00", "openPrice": "99", "highPrice": "101", "lowPrice": "98",
        "closePrice": str(종가), "volume": str(거래량), "currency": "KRW",
    }


def 응답(봉들: list[dict], next_before: str | None = None) -> httpx.Response:
    return httpx.Response(200, json={"result": {"candles": 봉들, "nextBefore": next_before}})


class Test키움_모양으로_맞추기:
    @respx.mock
    def test_봉_끝_시각을_1분_당겨_봉_시작_시각으로_둔다(self, respx_mock, 토큰_발급):
        respx_mock.get(CANDLES_URL).mock(return_value=응답([봉("2026-09-28T09:01:00")]))

        [첫_봉] = candles.fetch_today_minute_candles("005930")

        assert 첫_봉.date_time == datetime(2026, 9, 28, 9, 0)

    @respx.mock
    def test_체결_없는_빈_봉은_버린다(self, respx_mock, 토큰_발급):
        """두면 스파이크의 직전 평균과 이평이 키움 때와 달라진다."""
        respx_mock.get(CANDLES_URL).mock(return_value=응답([
            봉("2026-09-28T09:02:00"), 봉("2026-09-28T09:01:00", 거래량=0),
        ]))

        assert [c.date_time.minute for c in candles.fetch_today_minute_candles("005930")] == [1]

    @respx.mock
    def test_시각_오름차순으로_돌려준다(self, respx_mock, 토큰_발급):
        respx_mock.get(CANDLES_URL).mock(return_value=응답([
            봉("2026-09-28T09:03:00"), 봉("2026-09-28T09:02:00"), 봉("2026-09-28T09:01:00"),
        ]))

        시각들 = [c.date_time for c in candles.fetch_today_minute_candles("005930")]

        assert 시각들 == sorted(시각들)

    @respx.mock
    def test_거래대금은_종가에_거래량을_곱해_근사한다(self, respx_mock, 토큰_발급):
        respx_mock.get(CANDLES_URL).mock(return_value=응답([봉("2026-09-28T09:01:00", 종가=200, 거래량=3)]))

        assert candles.fetch_today_minute_candles("005930")[0].trading_value == 600

    @respx.mock
    def test_랭킹_코드의_SOR_접미사를_떼고_부른다(self, respx_mock, 토큰_발급):
        route = respx_mock.get(CANDLES_URL).mock(return_value=응답([]))

        candles.fetch_today_minute_candles("005930_AL")

        assert route.calls[0].request.url.params["symbol"] == "005930"


class Test하루치_받기:
    @respx.mock
    def test_전날_봉이_나올_때까지_거꾸로_넘긴다(self, respx_mock, 토큰_발급):
        route = respx_mock.get(CANDLES_URL).mock(side_effect=[
            응답([봉("2026-09-28T09:02:00")], next_before="2026-09-28T09:01:00+09:00"),
            응답([봉("2026-09-28T09:01:00"), 봉("2026-09-25T20:00:00")], next_before="2026-09-25T19:59:00+09:00"),
        ])

        받은_봉 = candles.fetch_today_minute_candles("005930")

        assert route.call_count == 2
        assert [c.date_time.date() for c in 받은_봉] == [오늘, 오늘]    # 전날 봉은 빠진다

    @respx.mock
    def test_장_시작_전이면_빈_목록이다(self, respx_mock, 토큰_발급):
        respx_mock.get(CANDLES_URL).mock(return_value=응답(
            [봉("2026-09-25T20:00:00")], next_before="2026-09-25T19:59:00+09:00",
        ))

        assert candles.fetch_today_minute_candles("005930") == []


class Test이어_받기:
    @respx.mock
    def test_기준_시각_이후_봉만_몇_개_청해서_받는다(self, respx_mock, 토큰_발급):
        route = respx_mock.get(CANDLES_URL).mock(return_value=응답([
            봉("2026-09-28T10:00:00"), 봉("2026-09-28T09:59:00"), 봉("2026-09-28T09:58:00"),
        ], next_before="2026-09-28T09:57:00+09:00"))

        받은_봉 = candles.fetch_today_minute_candles("005930", since=datetime(2026, 9, 28, 9, 58))

        assert route.call_count == 1
        assert int(route.calls[0].request.url.params["count"]) < 200
        assert [c.date_time.minute for c in 받은_봉] == [58, 59]


class Test실패:
    @respx.mock
    def test_한도_초과면_잠깐_쉬고_한_번_더_부른다(self, respx_mock, 토큰_발급, monkeypatch):
        쉼: list[float] = []
        monkeypatch.setattr(candles, "sleep", 쉼.append)
        route = respx_mock.get(CANDLES_URL).mock(side_effect=[
            httpx.Response(429), 응답([봉("2026-09-28T09:01:00")]),
        ])

        받은_봉 = candles.fetch_today_minute_candles("005930")

        assert route.call_count == 2
        assert 쉼 == [1.0]
        assert len(받은_봉) == 1

    @respx.mock
    def test_다시_불러도_한도_초과면_예외로_올린다(self, respx_mock, 토큰_발급, monkeypatch):
        """한 번만 다시 부른다 — 계속 넘치면 부르는 쪽(다음 갱신 회차)에 맡긴다."""
        monkeypatch.setattr(candles, "sleep", lambda _: None)
        route = respx_mock.get(CANDLES_URL).mock(return_value=httpx.Response(429))

        with pytest.raises(httpx.HTTPStatusError):
            candles.fetch_today_minute_candles("005930")
        assert route.call_count == 2

    @respx.mock
    def test_한도_초과가_아닌_오류는_다시_부르지_않는다(self, respx_mock, 토큰_발급, monkeypatch):
        monkeypatch.setattr(candles, "sleep", lambda _: None)
        route = respx_mock.get(CANDLES_URL).mock(return_value=httpx.Response(500))

        with pytest.raises(httpx.HTTPStatusError):
            candles.fetch_today_minute_candles("005930")
        assert route.call_count == 1

    @respx.mock
    def test_오류는_예외로_올린다(self, respx_mock, 토큰_발급):
        """이어 붙이는 쪽이 기존 봉을 지키려면 실패를 알아야 한다."""
        respx_mock.get(CANDLES_URL).mock(return_value=httpx.Response(500))

        with pytest.raises(httpx.HTTPStatusError):
            candles.fetch_today_minute_candles("005930")

    @respx.mock
    def test_수정주가로_청한다(self, respx_mock, 토큰_발급):
        """과거 분봉(키움)이 수정주가라 오늘 봉도 같은 기준이어야 돌파선이 맞는다."""
        route = respx_mock.get(CANDLES_URL).mock(return_value=응답([]))

        candles.fetch_today_minute_candles("005930")

        assert route.calls[0].request.url.params["adjusted"] == "true"
