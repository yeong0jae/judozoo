from datetime import date

import httpx
import pytest
import respx

from backend.platform.kis import client as kis_client
from backend.platform.kis.overseas_index import fetch_index_daily_close
from backend.platform.kis.overseas_product import fetch_market_cap
from backend.platform.kis.overseas_ranking import fetch_trading_value_ranking

BASE = "https://openapi.koreainvestment.com:9443"
RANKING_URL = f"{BASE}/uapi/overseas-stock/v1/ranking/trade-pbmn"
PRODUCT_URL = f"{BASE}/uapi/overseas-price/v1/quotations/search-info"
INDEX_URL = f"{BASE}/uapi/overseas-price/v1/quotations/inquire-daily-chartprice"
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


class Test거래대금순위:
    @respx.mock
    def test_응답_필드를_그대로_전달한다(self, respx_mock, 토큰_발급):
        """등락 방향은 sign이 정하고 diff·rate는 절댓값이라, 파싱은 쓰는 쪽에 맡긴다."""
        respx_mock.get(RANKING_URL).mock(
            return_value=httpx.Response(
                200,
                json={
                    "rt_cd": "0", "msg_cd": "", "msg1": "",
                    "output2": [{
                        "rank": "1", "excd": "NAS", "symb": "AAPL", "name": "애플",
                        "last": "230.5", "sign": "5", "diff": "1.2", "rate": "0.52",
                        "tvol": "50000000", "tamt": "11500000000", "ename": "APPLE INC",
                    }],
                },
            )
        )

        items = fetch_trading_value_ranking("NAS")

        assert items[0].symb == "AAPL"
        assert items[0].sign == "5", "5=하락"
        assert items[0].rate == "0.52", "절댓값 그대로"

    @respx.mock
    def test_없는_필드는_빈_문자열로_채운다(self, respx_mock, 토큰_발급):
        respx_mock.get(RANKING_URL).mock(
            return_value=httpx.Response(
                200, json={"rt_cd": "0", "msg1": "", "output2": [{"symb": "AAPL"}]}
            )
        )

        assert fetch_trading_value_ranking("NAS")[0].ename == ""

    @respx.mock
    def test_오류_코드가_오면_예외를_올린다(self, respx_mock, 토큰_발급):
        respx_mock.get(RANKING_URL).mock(
            return_value=httpx.Response(200, json={"rt_cd": "1", "msg1": "조회 실패"})
        )

        with pytest.raises(RuntimeError, match="거래대금순위 오류"):
            fetch_trading_value_ranking("NAS")

    @respx.mock
    def test_거래소별로_따로_캐시한다(self, respx_mock, 토큰_발급):
        route = respx_mock.get(RANKING_URL).mock(
            return_value=httpx.Response(200, json={"rt_cd": "0", "msg1": "", "output2": [{"symb": "A"}]})
        )

        fetch_trading_value_ranking("NAS")
        fetch_trading_value_ranking("NAS")
        fetch_trading_value_ranking("NYS")

        assert route.call_count == 2


class Test시가총액:
    @respx.mock
    def test_상장주식수와_현재가를_곱한다(self, respx_mock, 토큰_발급):
        respx_mock.get(PRODUCT_URL).mock(
            return_value=httpx.Response(
                200,
                json={"rt_cd": "0", "msg1": "",
                      "output": {"lstg_stck_num": "1000000", "ovrs_now_pric1": "230.5"}},
            )
        )

        assert fetch_market_cap("NAS", "AAPL") == 230_500_000

    @respx.mock
    def test_거래소마다_상품유형코드가_다르다(self, respx_mock, 토큰_발급):
        route = respx_mock.get(PRODUCT_URL).mock(
            return_value=httpx.Response(200, json={"rt_cd": "0", "msg1": "", "output": None})
        )

        for excd, expected in (("NAS", "512"), ("NYS", "513"), ("AMS", "529")):
            fetch_market_cap(excd, "AAPL")
            assert route.calls.last.request.url.params["PRDT_TYPE_CD"] == expected

    def test_모르는_거래소는_호출_전에_막는다(self):
        with pytest.raises(ValueError, match="지원하지 않는 거래소"):
            fetch_market_cap("XXX", "AAPL")

    @pytest.mark.parametrize(
        "output",
        [
            {"lstg_stck_num": "0", "ovrs_now_pric1": "230.5"},
            {"lstg_stck_num": "1000", "ovrs_now_pric1": "0"},
            {"lstg_stck_num": "", "ovrs_now_pric1": "230.5"},
            None,
        ],
        ids=["주식수0", "가격0", "빈값", "output없음"],
    )
    def test_값이_온전하지_않으면_시가총액을_주지_않는다(self, respx_mock, 토큰_발급, output):
        respx_mock.get(PRODUCT_URL).mock(
            return_value=httpx.Response(200, json={"rt_cd": "0", "msg1": "", "output": output})
        )

        assert fetch_market_cap("NAS", "AAPL") is None

    @respx.mock
    def test_결과가_없으면_캐시하지_않아_다음에_다시_시도한다(self, respx_mock, 토큰_발급):
        route = respx_mock.get(PRODUCT_URL).mock(
            return_value=httpx.Response(200, json={"rt_cd": "0", "msg1": "", "output": None})
        )

        fetch_market_cap("NAS", "AAPL")
        fetch_market_cap("NAS", "AAPL")

        assert route.call_count == 2


class Test해외지수:
    def 지수응답(self, price="20000.5", prev="20100.0", ctrt="0.5", dates=("20260824", "20260825")):
        return httpx.Response(
            200,
            json={
                "rt_cd": "0", "msg_cd": "", "msg1": "",
                "output1": {
                    "ovrs_nmix_prpr": price,
                    "ovrs_nmix_prdy_clpr": prev,
                    "prdy_ctrt": ctrt,
                    "hts_kor_isnm": "나스닥종합",
                },
                "output2": [{"stck_bsop_date": d} for d in dates],
            },
        )

    @respx.mock
    def test_등락_방향은_전일종가와_비교해_정한다(self, respx_mock, 토큰_발급):
        """prdy_ctrt는 부호가 붙어 오기도 해 크기만 쓴다."""
        respx_mock.get(INDEX_URL).mock(return_value=self.지수응답(price="20000.5", prev="20100.0"))

        quote = fetch_index_daily_close("COMP", date(2026, 8, 20), date(2026, 8, 25))

        assert quote.change_rate == -0.5, "현재가 < 전일종가 → 음수"

    @respx.mock
    def test_상승이면_양수로_준다(self, respx_mock, 토큰_발급):
        respx_mock.get(INDEX_URL).mock(return_value=self.지수응답(price="20200.0", prev="20100.0"))

        assert fetch_index_daily_close("COMP", date(2026, 8, 20), date(2026, 8, 25)).change_rate == 0.5

    @respx.mock
    def test_부호가_붙어_와도_크기만_쓴다(self, respx_mock, 토큰_발급):
        respx_mock.get(INDEX_URL).mock(
            return_value=self.지수응답(price="20000.5", prev="20100.0", ctrt="-0.5")
        )

        assert fetch_index_daily_close("COMP", date(2026, 8, 20), date(2026, 8, 25)).change_rate == -0.5

    @respx.mock
    def test_영업일은_일자별_응답의_최신_날짜를_쓴다(self, respx_mock, 토큰_발급):
        """미국 휴장일에도 종가가 찍히므로 조회 종료일이 아니라 실제 찍힌 날짜를 쓴다."""
        respx_mock.get(INDEX_URL).mock(
            return_value=self.지수응답(dates=("20260821", "20260824", "20260822"))
        )

        quote = fetch_index_daily_close("COMP", date(2026, 8, 20), date(2026, 8, 25))

        assert quote.trade_date == date(2026, 8, 24)

    @respx.mock
    def test_일자별_응답이_비면_조회_종료일로_대체한다(self, respx_mock, 토큰_발급):
        respx_mock.get(INDEX_URL).mock(return_value=self.지수응답(dates=()))

        quote = fetch_index_daily_close("COMP", date(2026, 8, 20), date(2026, 8, 25))

        assert quote.trade_date == date(2026, 8, 25)

    @respx.mock
    def test_종목명이_없으면_코드를_이름으로_쓴다(self, respx_mock, 토큰_발급):
        respx_mock.get(INDEX_URL).mock(
            return_value=httpx.Response(
                200,
                json={"rt_cd": "0", "output1": {"ovrs_nmix_prpr": "100"}, "output2": []},
            )
        )

        assert fetch_index_daily_close("COMP", date(2026, 8, 20), date(2026, 8, 25)).name == "COMP"

    @pytest.mark.parametrize(
        "response",
        [
            httpx.Response(200, json={"rt_cd": "1", "msg1": "오류"}),
            httpx.Response(200, json={"rt_cd": "0", "output1": None}),
            httpx.Response(200, json={"rt_cd": "0", "output1": {"ovrs_nmix_prpr": "-"}}),
            httpx.Response(500),
        ],
        ids=["오류코드", "기본정보없음", "가격파싱불가", "HTTP오류"],
    )
    def test_온전하지_않으면_캡처가_스킵하도록_None을_준다(self, respx_mock, 토큰_발급, response):
        respx_mock.get(INDEX_URL).mock(return_value=response)

        assert fetch_index_daily_close("COMP", date(2026, 8, 20), date(2026, 8, 25)) is None
