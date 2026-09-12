import httpx
import pytest
import respx

from backend.platform.yahoo import client as yahoo
from backend.platform.yahoo.client import fetch_candles, fetch_extended_quote, fetch_quote

CHART_URL = "https://query1.finance.yahoo.com/v8/finance/chart/NQ%3DF"
AAPL_URL = "https://query1.finance.yahoo.com/v8/finance/chart/AAPL"


@pytest.fixture(autouse=True)
def 야후_초기화():
    yahoo.reset()
    yield
    yahoo.reset()


def 차트(meta: dict | None = None, timestamps: list | None = None, quote: dict | None = None):
    result: dict = {}
    if meta is not None:
        result["meta"] = meta
    if timestamps is not None:
        result["timestamp"] = timestamps
    if quote is not None:
        result["indicators"] = {"quote": [quote]}
    return httpx.Response(200, json={"chart": {"result": [result], "error": None}})


class Test시세_요약:
    @respx.mock
    def test_현재가와_직전_세션_종가를_읽는다(self, respx_mock):
        respx_mock.get(CHART_URL).mock(
            return_value=차트(
                meta={"shortName": "Nasdaq 100", "regularMarketPrice": 20100.5, "previousClose": 20000.0}
            )
        )

        quote = fetch_quote("NQ=F")

        assert quote.name == "Nasdaq 100"
        assert quote.price == 20100.5
        assert quote.prev_close == 20000.0

    @respx.mock
    def test_조회창_직전_종가보다_전일종가를_우선한다(self, respx_mock):
        """chartPreviousClose는 range에 따라 값이 달라져 기준가로 쓰기 부적절하다."""
        respx_mock.get(CHART_URL).mock(
            return_value=차트(
                meta={
                    "regularMarketPrice": 100.0,
                    "previousClose": 90.0,
                    "chartPreviousClose": 80.0,
                }
            )
        )

        assert fetch_quote("NQ=F").prev_close == 90.0

    @respx.mock
    def test_전일종가가_없으면_조회창_직전_종가로_대체한다(self, respx_mock):
        respx_mock.get(CHART_URL).mock(
            return_value=차트(meta={"regularMarketPrice": 100.0, "chartPreviousClose": 80.0})
        )

        assert fetch_quote("NQ=F").prev_close == 80.0

    @respx.mock
    def test_현재가가_없으면_시세를_주지_않는다(self, respx_mock):
        respx_mock.get(CHART_URL).mock(return_value=차트(meta={"previousClose": 90.0}))

        assert fetch_quote("NQ=F") is None


class Test확장시간_시세:
    """meta는 정규장 값만 담는다. 프리·애프터마켓 중엔 마지막 캔들을 현재가로 써야 한다."""

    @respx.mock
    def test_정규장_이후_체결이_있으면_마지막_캔들을_현재가로_쓴다(self, respx_mock):
        respx_mock.get(AAPL_URL).mock(
            return_value=차트(
                meta={"regularMarketPrice": 200.0, "regularMarketTime": 1000, "previousClose": 190.0},
                timestamps=[900, 1000, 1100],  # 1100은 정규장 마감 이후
                quote={"close": [198.0, 200.0, 205.0]},
            )
        )

        quote = fetch_extended_quote("AAPL")

        assert quote.price == 205.0, "애프터마켓 체결이 현재가"
        assert quote.prev_close == 200.0, "직전 정규장 종가가 기준가"

    @respx.mock
    def test_정규장_중에는_meta를_그대로_쓴다(self, respx_mock):
        respx_mock.get(AAPL_URL).mock(
            return_value=차트(
                meta={"regularMarketPrice": 200.0, "regularMarketTime": 1100, "previousClose": 190.0},
                timestamps=[900, 1000, 1100],
                quote={"close": [198.0, 199.0, 200.0]},
            )
        )

        quote = fetch_extended_quote("AAPL")

        assert quote.price == 200.0
        assert quote.prev_close == 190.0

    @respx.mock
    def test_거래가_없던_분은_건너뛰고_마지막_체결을_찾는다(self, respx_mock):
        respx_mock.get(AAPL_URL).mock(
            return_value=차트(
                meta={"regularMarketPrice": 200.0, "regularMarketTime": 1000, "previousClose": 190.0},
                timestamps=[1100, 1200, 1300],
                quote={"close": [205.0, None, None]},
            )
        )

        assert fetch_extended_quote("AAPL").price == 205.0


class Test캔들:
    @respx.mock
    def test_시각을_KST로_바꿔_날짜와_시간을_나눈다(self, respx_mock):
        # 1735689600 = 2025-01-01 00:00:00 UTC = 2025-01-01 09:00:00 KST
        respx_mock.get(AAPL_URL).mock(
            return_value=차트(
                timestamps=[1735689600],
                quote={"close": [100.0], "open": [99.0], "high": [101.0], "low": [98.0], "volume": [5]},
            )
        )

        bar = fetch_candles("AAPL", "1m", "1d")[0]

        assert bar.date == "2025-01-01"
        assert bar.time == "09:00:00"
        assert (bar.open, bar.high, bar.low, bar.close, bar.volume) == (99.0, 101.0, 98.0, 100.0, 5.0)

    @respx.mock
    def test_거래가_없던_분은_버린다(self, respx_mock):
        respx_mock.get(AAPL_URL).mock(
            return_value=차트(timestamps=[1735689600, 1735689660], quote={"close": [100.0, None]})
        )

        assert len(fetch_candles("AAPL", "1m", "1d")) == 1

    @respx.mock
    def test_시가고가저가가_없으면_종가로_채운다(self, respx_mock):
        respx_mock.get(AAPL_URL).mock(
            return_value=차트(timestamps=[1735689600], quote={"close": [100.0]})
        )

        bar = fetch_candles("AAPL", "1m", "1d")[0]

        assert (bar.open, bar.high, bar.low, bar.volume) == (100.0, 100.0, 100.0, 0.0)

    @respx.mock
    def test_같은_시각이_두_번_오면_뒤엣것으로_덮는다(self, respx_mock):
        """세션 마감 봉이 거래량 0짜리 중복으로 한 번 더 온다. 차트는 시각이 유일해야 한다."""
        respx_mock.get(AAPL_URL).mock(
            return_value=차트(
                timestamps=[1735689600, 1735689600],
                quote={"close": [100.0, 102.0], "volume": [5, 0]},
            )
        )

        bars = fetch_candles("AAPL", "1m", "1d")

        assert len(bars) == 1
        assert bars[0].close == 102.0

    @respx.mock
    def test_시각_오름차순으로_준다(self, respx_mock):
        respx_mock.get(AAPL_URL).mock(
            return_value=차트(
                timestamps=[1735689720, 1735689600, 1735689660],
                quote={"close": [102.0, 100.0, 101.0]},
            )
        )

        assert [b.close for b in fetch_candles("AAPL", "1m", "1d")] == [100.0, 101.0, 102.0]


class Test요청_형식:
    @respx.mock
    def test_차단을_피하려_User_Agent를_붙인다(self, respx_mock):
        route = respx_mock.get(AAPL_URL).mock(return_value=차트(meta={}))

        fetch_quote("AAPL")

        assert "Mozilla" in route.calls.last.request.headers["user-agent"]

    @respx.mock
    def test_확장시간_조회에만_includePrePost를_붙인다(self, respx_mock):
        route = respx_mock.get(AAPL_URL).mock(return_value=차트(meta={"regularMarketPrice": 1.0}))

        fetch_quote("AAPL")
        assert "includePrePost" not in route.calls.last.request.url.params

        fetch_extended_quote("AAPL")
        assert route.calls.last.request.url.params["includePrePost"] == "true"


class Test응답이_정상이_아니면:
    @respx.mock
    def test_야후가_오류를_주면_빈_결과로_처리한다(self, respx_mock):
        respx_mock.get(AAPL_URL).mock(
            return_value=httpx.Response(
                200, json={"chart": {"result": None, "error": {"code": "Not Found"}}}
            )
        )

        assert fetch_quote("AAPL") is None
        assert fetch_candles("AAPL", "1m", "1d") == []

    @respx.mock
    def test_HTTP_오류에도_예외를_밖으로_던지지_않는다(self, respx_mock):
        respx_mock.get(AAPL_URL).mock(return_value=httpx.Response(500))

        assert fetch_quote("AAPL") is None
        assert fetch_candles("AAPL", "1m", "1d") == []

    @respx.mock
    def test_연결이_끊겨도_빈_결과를_준다(self, respx_mock):
        respx_mock.get(AAPL_URL).mock(side_effect=httpx.ConnectError("끊김"))

        assert fetch_candles("AAPL", "1m", "1d") == []
