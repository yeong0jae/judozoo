import httpx
import pytest
import respx

from backend.platform.kis import client as kis_client
from backend.platform.kis.overseas_product import fetch_market_cap
from backend.platform.kis.overseas_ranking import fetch_trading_value_ranking

BASE = "https://openapi.koreainvestment.com:9443"
RANKING_URL = f"{BASE}/uapi/overseas-stock/v1/ranking/trade-pbmn"
PRODUCT_URL = f"{BASE}/uapi/overseas-price/v1/quotations/search-info"
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
