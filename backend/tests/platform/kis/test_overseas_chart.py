import httpx
import pytest
import respx

from backend.platform.kis import client as kis_client
from backend.platform.kis.overseas_chart import (
    fetch_daily_candles,
    fetch_minute_candles,
)

BASE = "https://openapi.koreainvestment.com:9443"
MINUTE_URL = f"{BASE}/uapi/overseas-price/v1/quotations/inquire-time-itemchartprice"
DAILY_URL = f"{BASE}/uapi/overseas-price/v1/quotations/dailyprice"
TOKEN_URL = f"{BASE}/oauth2/tokenP"


@pytest.fixture(autouse=True)
def kis_초기화():
    kis_client.reset()
    yield
    kis_client.reset()


@pytest.fixture
def 토큰_발급(respx_mock):
    respx_mock.post(TOKEN_URL).mock(
        return_value=httpx.Response(200, json={"access_token": "test-access-token"})
    )


def 분봉(tymd: str, xymd: str, xhms: str, kymd: str, khms: str, last: str = "100") -> dict:
    return {
        "tymd": tymd, "xymd": xymd, "xhms": xhms, "kymd": kymd, "khms": khms,
        "open": "99", "high": "101", "low": "98", "last": last,
        "evol": "1000", "eamt": "99000",
    }


def 분봉응답(items: list[dict]) -> httpx.Response:
    return httpx.Response(200, json={"rt_cd": "0", "msg1": "정상", "output2": items})


def 일봉응답(items: list[dict]) -> httpx.Response:
    return httpx.Response(200, json={"rt_cd": "0", "msg1": "정상", "output2": items})


def 일봉(xymd: str, clos: str = "100") -> dict:
    return {"xymd": xymd, "clos": clos, "open": "99", "high": "101", "low": "98", "tvol": "5000"}


class Test분봉_페이징:
    @respx.mock
    def test_다음조회_커서는_현지시각에서_1분을_뺀_값이다(self, respx_mock, 토큰_발급):
        """한국시각(kymd+khms)을 커서로 넣으면 엉뚱한 구간이 온다."""
        페이지 = [
            분봉("20260825", "20260825", "093000", "20260825", "223000"),
            분봉("20260825", "20260825", "092900", "20260825", "222900"),  # 마지막 = 가장 이른 봉
        ]
        route = respx_mock.get(MINUTE_URL).mock(
            side_effect=[분봉응답(페이지), 분봉응답([])]
        )

        fetch_minute_candles("NAS", "AAPL")

        두번째_요청 = route.calls[1].request.url.params
        assert 두번째_요청["KEYB"] == "20260825092800", "현지 09:29에서 1분 뺀 값"
        assert 두번째_요청["NEXT"] == "1"

    @respx.mock
    def test_첫_페이지에는_커서를_비워_보낸다(self, respx_mock, 토큰_발급):
        route = respx_mock.get(MINUTE_URL).mock(return_value=분봉응답([]))

        fetch_minute_candles("NAS", "AAPL")

        params = route.calls.last.request.url.params
        assert params["KEYB"] == ""
        assert params["NEXT"] == ""

    @respx.mock
    def test_거래일이_기준을_넘으면_더_받지_않는다(self, respx_mock, 토큰_발급):
        """SESSION_DAYS(2)를 넘는 거래일이 보이면 충분히 받은 것."""
        route = respx_mock.get(MINUTE_URL).mock(
            side_effect=[
                분봉응답([분봉("20260825", "20260825", "093000", "20260825", "223000")]),
                분봉응답([분봉("20260824", "20260824", "093000", "20260824", "223000")]),
                분봉응답([분봉("20260823", "20260823", "093000", "20260823", "223000")]),
                분봉응답([분봉("20260822", "20260822", "093000", "20260822", "223000")]),
            ]
        )

        fetch_minute_candles("NAS", "AAPL")

        assert route.call_count == 3, "3번째에서 거래일 3개가 되어 중단"

    @respx.mock
    def test_최신_두_거래일치만_남긴다(self, respx_mock, 토큰_발급):
        respx_mock.get(MINUTE_URL).mock(
            side_effect=[
                분봉응답([분봉("20260825", "20260825", "093000", "20260825", "223000", last="1")]),
                분봉응답([분봉("20260824", "20260824", "093000", "20260824", "223000", last="2")]),
                분봉응답([분봉("20260823", "20260823", "093000", "20260823", "223000", last="3")]),
            ]
        )

        candles = fetch_minute_candles("NAS", "AAPL")

        assert sorted(c.close for c in candles) == [1.0, 2.0], "20260823은 버려진다"

    @respx.mock
    def test_빈_페이지가_오면_멈춘다(self, respx_mock, 토큰_발급):
        route = respx_mock.get(MINUTE_URL).mock(return_value=분봉응답([]))

        assert fetch_minute_candles("NAS", "AAPL") == []
        assert route.call_count == 1


class Test분봉_변환:
    @respx.mock
    def test_봉의_시각은_한국기준을_쓴다(self, respx_mock, 토큰_발급):
        respx_mock.get(MINUTE_URL).mock(
            side_effect=[
                분봉응답([분봉("20260825", "20260825", "093000", "20260825", "223000")]),
                분봉응답([]),
            ]
        )

        candle = fetch_minute_candles("NAS", "AAPL")[0]

        assert candle.date_time.hour == 22, "현지 09:30이 아니라 한국 22:30"
        assert candle.date_time.minute == 30

    @respx.mock
    def test_시간이_6자리보다_짧으면_0을_채운다(self, respx_mock, 토큰_발급):
        respx_mock.get(MINUTE_URL).mock(
            side_effect=[분봉응답([분봉("20260825", "20260825", "93000", "20260825", "93000")]), 분봉응답([])]
        )

        candle = fetch_minute_candles("NAS", "AAPL")[0]

        assert (candle.date_time.hour, candle.date_time.minute) == (9, 30)

    @respx.mock
    def test_시각을_해석할_수_없는_봉은_버린다(self, respx_mock, 토큰_발급):
        respx_mock.get(MINUTE_URL).mock(
            side_effect=[
                분봉응답([
                    분봉("20260825", "20260825", "093000", "이상", "223000"),
                    분봉("20260825", "20260825", "092900", "20260825", "222900"),
                ]),
                분봉응답([]),
            ]
        )

        assert len(fetch_minute_candles("NAS", "AAPL")) == 1

    @respx.mock
    def test_숫자가_아닌_값은_0으로_둔다(self, respx_mock, 토큰_발급):
        item = 분봉("20260825", "20260825", "093000", "20260825", "223000")
        item["evol"] = ""
        item["eamt"] = "-"
        respx_mock.get(MINUTE_URL).mock(side_effect=[분봉응답([item]), 분봉응답([])])

        candle = fetch_minute_candles("NAS", "AAPL")[0]

        assert (candle.volume, candle.trading_value) == (0, 0.0)


class Test분봉_실패:
    @respx.mock
    def test_오류_코드가_오면_예외를_올린다(self, respx_mock, 토큰_발급):
        """페이징 도중 조용히 잘리면 봉이 비는 걸 눈치채기 어렵다."""
        respx_mock.get(MINUTE_URL).mock(
            return_value=httpx.Response(200, json={"rt_cd": "1", "msg1": "조회 오류", "output2": None})
        )

        with pytest.raises(RuntimeError, match="해외 분봉 오류"):
            fetch_minute_candles("NAS", "AAPL")


class Test일봉_페이징:
    @respx.mock
    def test_다음_커서는_마지막_일자에서_하루를_뺀_값이다(self, respx_mock, 토큰_발급):
        첫페이지 = [일봉(f"2026081{i % 10}") for i in range(100)]
        첫페이지[-1] = 일봉("20260501")
        route = respx_mock.get(DAILY_URL).mock(
            side_effect=[일봉응답(첫페이지), 일봉응답([일봉("20260430")])]
        )

        fetch_daily_candles("NAS", "AAPL")

        assert route.calls[1].request.url.params["BYMD"] == "20260430"

    @respx.mock
    def test_100건_미만이_오면_더_없다고_보고_멈춘다(self, respx_mock, 토큰_발급):
        route = respx_mock.get(DAILY_URL).mock(return_value=일봉응답([일봉("20260825")]))

        fetch_daily_candles("NAS", "AAPL")

        assert route.call_count == 1

    @respx.mock
    def test_목표_건수를_넘으면_잘라서_준다(self, respx_mock, 토큰_발급):
        페이지 = [일봉("20260825") for _ in range(100)]
        respx_mock.get(DAILY_URL).mock(
            side_effect=[일봉응답(페이지), 일봉응답(페이지), 일봉응답(페이지)]
        )

        assert len(fetch_daily_candles("NAS", "AAPL")) == 200


class Test일봉_실패:
    @respx.mock
    def test_오류_코드가_오면_빈_목록을_준다(self, respx_mock, 토큰_발급):
        """일봉은 없으면 차트만 짧아지고 끝이라 호출측을 깨우지 않는다."""
        respx_mock.get(DAILY_URL).mock(
            return_value=httpx.Response(200, json={"rt_cd": "1", "msg1": "오류", "output2": None})
        )

        assert fetch_daily_candles("NAS", "AAPL") == []

    @respx.mock
    def test_HTTP_오류에도_빈_목록을_준다(self, respx_mock, 토큰_발급):
        respx_mock.get(DAILY_URL).mock(return_value=httpx.Response(500))

        assert fetch_daily_candles("NAS", "AAPL") == []


class Test요청_형식:
    @respx.mock
    def test_분봉은_전일포함으로_요청한다(self, respx_mock, 토큰_발급):
        """장 초반에도 직전 세션 봉으로 채우기 위해서다."""
        route = respx_mock.get(MINUTE_URL).mock(return_value=분봉응답([]))

        fetch_minute_candles("NAS", "AAPL")

        params = route.calls.last.request.url.params
        assert params["PINC"] == "1"
        assert params["NMIN"] == "1"
        assert params["NREC"] == "120"
        assert route.calls.last.request.headers["tr_id"] == "HHDFS76950200"

    @respx.mock
    def test_일봉은_수정주가를_반영해_요청한다(self, respx_mock, 토큰_발급):
        route = respx_mock.get(DAILY_URL).mock(return_value=일봉응답([]))

        fetch_daily_candles("NAS", "AAPL")

        params = route.calls.last.request.url.params
        assert params["MODP"] == "1"
        assert params["GUBN"] == "0"
        assert route.calls.last.request.headers["tr_id"] == "HHDFS76240000"
