from datetime import datetime

import httpx
import pytest
import respx

from backend.overseasleadingstock import application
from backend.overseasleadingstock.domain import OverseasStockRank
from backend.platform.kis import client as kis_client

BASE = "https://openapi.koreainvestment.com:9443"
RANKING_URL = f"{BASE}/uapi/overseas-stock/v1/ranking/trade-pbmn"
PRODUCT_URL = f"{BASE}/uapi/overseas-price/v1/quotations/search-info"
MINUTE_URL = f"{BASE}/uapi/overseas-price/v1/quotations/inquire-time-itemchartprice"
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


def 순위행(symb: str, tamt: str, rate: str = "10.0", ename: str = "SOME CORP", sign: str = "2") -> dict:
    return {
        "rank": "1", "excd": "NAS", "symb": symb, "name": symb, "ename": ename,
        "last": "100", "sign": sign, "diff": "5", "rate": rate, "tvol": "1000", "tamt": tamt,
    }


def 순위응답(rows: list[dict]) -> httpx.Response:
    return httpx.Response(200, json={"rt_cd": "0", "msg1": "", "output2": rows})


def 거래소별_응답(respx_mock, nas: list[dict], nys: list[dict] = (), ams: list[dict] = ()):
    """세 거래소를 순서대로 응답한다 — NAS → NYS → AMS."""
    respx_mock.get(RANKING_URL).mock(
        side_effect=[순위응답(nas), 순위응답(list(nys)), 순위응답(list(ams))]
    )


class TestETF_판별:
    @pytest.mark.parametrize(
        "ename",
        ["SPDR S&P 500 ETF TRUST", "ISHARES CORE", "INVESCO QQQ", "VANGUARD TOTAL",
         "PROSHARES ULTRA", "DIREXION DAILY", "GLOBAL X FUNDS", "SOME ETN"],
    )
    def test_발행사_브랜드가_보이면_ETF로_본다(self, ename):
        assert OverseasStockRank(0, "NAS", "X", "X", ename, 0, 0, 0, 0).is_etf

    @pytest.mark.parametrize("ename", ["APPLE INC", "JPMORGAN CHASE & CO", "TRUST BANK CORP"])
    def test_일반기업은_통과시킨다(self, ename):
        """TRUST·FUND 같은 흔한 단어는 일부러 키워드에서 뺐다 — 오탐을 줄이려는 것."""
        assert not OverseasStockRank(0, "NAS", "X", "X", ename, 0, 0, 0, 0).is_etf


class Test랭킹:
    @respx.mock
    def test_세_거래소를_합쳐_거래대금_내림차순으로_순위를_매긴다(self, respx_mock, 토큰_발급):
        거래소별_응답(
            respx_mock,
            nas=[순위행("AAA", "100")],
            nys=[순위행("BBB", "300")],
            ams=[순위행("CCC", "200")],
        )

        ranking = application.get_candidates(0.0)

        assert [(r.rank, r.symbol) for r in ranking] == [(1, "BBB"), (2, "CCC"), (3, "AAA")]

    @respx.mock
    def test_ETF는_풀에서_제외하되_순위는_ETF를_포함해_매긴다(self, respx_mock, 토큰_발급):
        """국내는 키움 원본 순위(ETF 포함)를 쓴다 — 여기서 다시 매기면 두 화면의 "몇 위"가 다른 뜻이 된다."""
        거래소별_응답(
            respx_mock,
            nas=[순위행("SPY", "900", ename="SPDR S&P 500"), 순위행("AAA", "100")],
        )

        ranking = application.get_candidates(0.0)

        assert [(r.rank, r.symbol) for r in ranking] == [(2, "AAA")]

    @respx.mock
    def test_거래대금_1위여도_등락률에_미달하면_뺀다(self, respx_mock, 토큰_발급):
        """순위 예외를 없앴다 — 대장주를 보여주는 일은 화면 위쪽 주도주 구간이 맡는다."""
        거래소별_응답(
            respx_mock,
            nas=[
                순위행("A", "500", rate="-9.0"),
                순위행("B", "400", rate="-8.0"),
                순위행("C", "300", rate="-7.0"),
                순위행("D", "200", rate="-6.0"),
                순위행("E", "100", rate="9.0"),
            ],
        )

        assert [r.symbol for r in application.get_candidates(5.0)] == ["E"]

    @respx.mock
    def test_기준에_미달하면_전부_빠져_빈_목록이_된다(self, respx_mock, 토큰_발급):
        거래소별_응답(
            respx_mock,
            nas=[순위행(s, str(600 - i * 100), rate="1.0") for i, s in enumerate("ABCDE")],
        )

        assert application.get_candidates(5.0) == []

    @respx.mock
    def test_등락_방향은_sign으로_대비값에_부호를_준다(self, respx_mock, 토큰_발급):
        """rate는 부호 포함으로 오지만 diff(대비)는 절댓값이다."""
        거래소별_응답(respx_mock, nas=[순위행("A", "100", sign="5")])  # 5=하락

        assert application.get_candidates(0.0)[0].diff == -5.0


class Test시가총액_표기:
    @pytest.mark.parametrize(
        ("usd", "expected"),
        [
            (4_509_899_684_000, "$4.51T"),
            (1_000_000_000_000, "$1.00T"),
            (943_046_066_300, "$943B"),
            (2_000_000_000, "$2B"),
        ],
    )
    def test_1조_이상은_T_그_아래는_B로_적는다(self, usd, expected):
        assert application._format_usd_cap(usd) == expected


class Test종목_상세:
    @respx.mock
    def test_필터_세_개를_평가해_돌려준다(self, respx_mock, 토큰_발급):
        거래소별_응답(respx_mock, nas=[순위행("AAA", "100", rate="10.0")])
        respx_mock.get(PRODUCT_URL).mock(
            return_value=httpx.Response(
                200,
                json={"rt_cd": "0", "output": {"lstg_stck_num": "1000000000", "ovrs_now_pric1": "100"}},
            )
        )
        respx_mock.get(MINUTE_URL).mock(return_value=httpx.Response(200, json={"rt_cd": "0", "output2": []}))

        result = application.evaluate_stock("NAS", "AAA")

        이름 = [f.filter_name for f in result["filters"]]
        assert 이름 == ["거래대금순위", "당일 등락률", "시가총액"]
        assert [f.passed for f in result["filters"]] == [True, True, True]
        assert result["filters"][1].actual_value == "+10.00%"
        assert result["filters"][2].actual_value == "$100B"

    @respx.mock
    def test_시가총액을_못_구하면_조회_불가로_적고_탈락시킨다(self, respx_mock, 토큰_발급):
        거래소별_응답(respx_mock, nas=[순위행("AAA", "100")])
        respx_mock.get(PRODUCT_URL).mock(
            return_value=httpx.Response(200, json={"rt_cd": "0", "output": None})
        )
        respx_mock.get(MINUTE_URL).mock(return_value=httpx.Response(200, json={"rt_cd": "0", "output2": []}))

        시총필터 = application.evaluate_stock("NAS", "AAA")["filters"][2]

        assert 시총필터.actual_value == "조회 불가"
        assert 시총필터.passed is False

    @respx.mock
    def test_후보에_없는_종목은_조회할_수_없다(self, respx_mock, 토큰_발급):
        거래소별_응답(respx_mock, nas=[순위행("AAA", "100")])

        with pytest.raises(LookupError, match="후보에 없는 종목"):
            application.evaluate_stock("NAS", "ZZZ")


class Test해외_주도주_API:
    @respx.mock
    def test_랭킹_응답_형식이_기존_계약과_같다(self, respx_mock, 토큰_발급, 로그인_client):
        거래소별_응답(respx_mock, nas=[순위행("AAA", "1000", rate="10.0")])

        body = 로그인_client.get("/api/overseas-leading-stocks/candidates?minChangeRate=5").json()

        assert body["code"] == "SUCCESS"
        assert body["data"][0] == {
            "rank": 1, "exchange": "NAS", "symbol": "AAA", "name": "AAA",
            "ename": "SOME CORP", "price": 100.0, "diff": 5.0, "rate": 10.0,
            "tradingValue": 1000.0,
        }

    @respx.mock
    def test_등락률_인자는_허용_범위로_잘라_쓴다(self, respx_mock, 토큰_발급, 로그인_client):
        """사용자가 -12~7 밖의 값을 보내도 범위 안으로 맞춘다."""
        거래소별_응답(
            respx_mock,
            nas=[순위행(s, str(600 - i * 100), rate="6.0") for i, s in enumerate("ABCDE")],
        )

        body = 로그인_client.get("/api/overseas-leading-stocks/candidates?minChangeRate=99").json()

        # 99 → 7로 잘리므로 6.0짜리는 하나도 남지 않는다
        assert body["data"] == []

    @respx.mock
    def test_거래소와_심볼은_대문자로_맞춰_조회한다(self, respx_mock, 토큰_발급, 로그인_client):
        거래소별_응답(respx_mock, nas=[순위행("AAA", "100")])
        respx_mock.get(PRODUCT_URL).mock(
            return_value=httpx.Response(200, json={"rt_cd": "0", "output": None})
        )
        respx_mock.get(MINUTE_URL).mock(return_value=httpx.Response(200, json={"rt_cd": "0", "output2": []}))

        body = 로그인_client.get("/api/overseas-leading-stocks/nas/aaa").json()

        assert body["data"]["symbol"] == "AAA"

    @respx.mock
    def test_잘못된_인자는_400으로_돌려준다(self, respx_mock, 토큰_발급, 로그인_client):
        """FastAPI 기본은 422지만 기존 API 계약은 400이다."""
        response = 로그인_client.get("/api/overseas-leading-stocks/candidates?minChangeRate=abc")

        assert response.status_code == 400
        assert response.json() == {"code": "INVALID_PARAMETER", "status": 400, "data": None}
