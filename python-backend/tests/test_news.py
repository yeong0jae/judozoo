import httpx
import pytest
import respx

from backend.news.domain import StockNews
from backend.platform.kis import client as kis_client
from backend.platform.kis.news import fetch_overseas_news, fetch_stock_news

BASE = "https://openapi.koreainvestment.com:9443"
DOMESTIC_URL = f"{BASE}/uapi/domestic-stock/v1/quotations/news-title"
OVERSEAS_URL = f"{BASE}/uapi/overseas-price/v1/quotations/news-title"
TOKEN_URL = f"{BASE}/oauth2/tokenP"


@pytest.fixture(autouse=True)
def kis_초기화():
    """토큰 캐시와 커넥션을 테스트마다 비운다."""
    kis_client.reset()
    yield
    kis_client.reset()


@pytest.fixture
def 토큰_발급(respx_mock):
    respx_mock.post(TOKEN_URL).mock(
        return_value=httpx.Response(200, json={"access_token": "test-access-token"})
    )


def 국내_응답(rows: list[dict]) -> httpx.Response:
    return httpx.Response(200, json={"rt_cd": "0", "output": rows})


class Test국내_뉴스:
    @respx.mock
    def test_제목과_작성시각을_읽는다(self, respx_mock, 토큰_발급):
        respx_mock.get(DOMESTIC_URL).mock(
            return_value=국내_응답(
                [
                    {
                        "cntt_usiq_srno": "12345",
                        "news_ofer_entp_code": "A",
                        "data_dt": "20260825",
                        "data_tm": "143000",
                        "hts_pbnt_titl_cntt": " 삼성전자 신고가 ",
                        "dorg": " 한국경제 ",
                    }
                ]
            )
        )

        news = fetch_stock_news("005930")

        assert len(news) == 1
        assert news[0].seq_no == "12345"
        assert news[0].title == "삼성전자 신고가", "앞뒤 공백은 제거돼야 한다"
        assert news[0].source == "한국경제"
        assert news[0].published_at.hour == 14

    @respx.mock
    def test_공시_제공업체_코드는_공시로_구분한다(self, respx_mock, 토큰_발급):
        respx_mock.get(DOMESTIC_URL).mock(
            return_value=국내_응답(
                [
                    {
                        "cntt_usiq_srno": "1",
                        "news_ofer_entp_code": "F",  # 장내 공시
                        "data_dt": "20260825",
                        "data_tm": "090000",
                        "hts_pbnt_titl_cntt": "단일판매 공급계약 체결",
                        "dorg": "공시",
                    },
                    {
                        "cntt_usiq_srno": "2",
                        "news_ofer_entp_code": "A",  # 언론사
                        "data_dt": "20260825",
                        "data_tm": "090100",
                        "hts_pbnt_titl_cntt": "장 초반 상승",
                        "dorg": "연합뉴스",
                    },
                ]
            )
        )

        news = fetch_stock_news("005930")

        assert [n.disclosure for n in news] == [True, False]

    @respx.mock
    def test_제목이_비면_그_건은_버린다(self, respx_mock, 토큰_발급):
        respx_mock.get(DOMESTIC_URL).mock(
            return_value=국내_응답(
                [
                    {"cntt_usiq_srno": "1", "data_dt": "20260825", "data_tm": "090000",
                     "hts_pbnt_titl_cntt": "   "},
                    {"cntt_usiq_srno": "2", "data_dt": "20260825", "data_tm": "090100",
                     "hts_pbnt_titl_cntt": "정상 제목"},
                ]
            )
        )

        assert [n.title for n in fetch_stock_news("005930")] == ["정상 제목"]

    @respx.mock
    def test_작성시각을_해석할_수_없으면_그_건은_버린다(self, respx_mock, 토큰_발급):
        respx_mock.get(DOMESTIC_URL).mock(
            return_value=국내_응답(
                [{"cntt_usiq_srno": "1", "data_dt": "이상한값", "data_tm": "090000",
                  "hts_pbnt_titl_cntt": "제목"}]
            )
        )

        assert fetch_stock_news("005930") == []

    @respx.mock
    def test_시간이_6자리보다_짧으면_0을_채워_해석한다(self, respx_mock, 토큰_발급):
        respx_mock.get(DOMESTIC_URL).mock(
            return_value=국내_응답(
                [{"cntt_usiq_srno": "1", "data_dt": "20260825", "data_tm": "930",
                  "hts_pbnt_titl_cntt": "제목"}]
            )
        )

        published_at = fetch_stock_news("005930")[0].published_at

        assert (published_at.hour, published_at.minute, published_at.second) == (0, 9, 30)


class Test응답이_정상이_아니면:
    """호출측이 빈 목록으로 표시하도록 예외를 밖으로 던지지 않는다."""

    @respx.mock
    def test_오류_코드가_오면_빈_목록을_준다(self, respx_mock, 토큰_발급):
        respx_mock.get(DOMESTIC_URL).mock(
            return_value=httpx.Response(
                200, json={"rt_cd": "1", "msg_cd": "EGW00201", "msg1": "초당 거래건수 초과"}
            )
        )

        assert fetch_stock_news("005930") == []

    @respx.mock
    def test_HTTP_오류에도_빈_목록을_준다(self, respx_mock, 토큰_발급):
        respx_mock.get(DOMESTIC_URL).mock(return_value=httpx.Response(500))

        assert fetch_stock_news("005930") == []

    @respx.mock
    def test_연결이_끊겨도_빈_목록을_준다(self, respx_mock, 토큰_발급):
        respx_mock.get(DOMESTIC_URL).mock(side_effect=httpx.ConnectError("끊김"))

        assert fetch_stock_news("005930") == []


class Test해외_뉴스:
    @respx.mock
    def test_거래소코드와_국가코드를_함께_보낸다(self, respx_mock, 토큰_발급):
        """SYMB만 보내면 KIS가 종목 필터를 걸어주지 않아 0건이 온다."""
        route = respx_mock.get(OVERSEAS_URL).mock(
            return_value=httpx.Response(200, json={"rt_cd": "0", "outblock1": []})
        )

        fetch_overseas_news("NAS", "AAPL")

        params = route.calls.last.request.url.params
        assert params["NATION_CD"] == "US"
        assert params["EXCHANGE_CD"] == "NAS"
        assert params["SYMB"] == "AAPL"

    @respx.mock
    def test_공시가_섞이지_않으므로_전부_기사로_본다(self, respx_mock, 토큰_발급):
        respx_mock.get(OVERSEAS_URL).mock(
            return_value=httpx.Response(
                200,
                json={
                    "rt_cd": "0",
                    "outblock1": [
                        {"news_key": "k1", "data_dt": "20260825", "data_tm": "120000",
                         "source": "Reuters", "title": "Apple hits record"}
                    ],
                },
            )
        )

        news = fetch_overseas_news("NAS", "AAPL")

        assert news[0].disclosure is False
        assert news[0].source == "Reuters"


class Test요청_형식:
    @respx.mock
    def test_종목코드_외_파라미터는_공백으로_보낸다(self, respx_mock, 토큰_발급):
        """KIS가 빈 문자열을 요구한다. 파라미터를 빼면 오류가 난다."""
        route = respx_mock.get(DOMESTIC_URL).mock(return_value=국내_응답([]))

        fetch_stock_news("005930")

        params = route.calls.last.request.url.params
        assert params["FID_INPUT_ISCD"] == "005930"
        assert params["FID_NEWS_OFER_ENTP_CODE"] == ""
        assert params["FID_INPUT_DATE_1"] == ""

    @respx.mock
    def test_인증_헤더를_붙인다(self, respx_mock, 토큰_발급):
        route = respx_mock.get(DOMESTIC_URL).mock(return_value=국내_응답([]))

        fetch_stock_news("005930")

        headers = route.calls.last.request.headers
        assert headers["authorization"] == "Bearer test-access-token"
        assert headers["tr_id"] == "FHKST01011800"
        assert headers["custtype"] == "P"

    @respx.mock
    def test_토큰은_한_번만_발급하고_재사용한다(self, respx_mock, 토큰_발급):
        respx_mock.get(DOMESTIC_URL).mock(return_value=국내_응답([]))

        fetch_stock_news("005930")
        fetch_stock_news("000660")

        assert respx_mock.post(TOKEN_URL).call_count == 1


class Test뉴스_API:
    @respx.mock
    def test_기존_응답_봉투_형식을_지킨다(self, respx_mock, 토큰_발급, client):
        respx_mock.get(DOMESTIC_URL).mock(
            return_value=국내_응답(
                [{"cntt_usiq_srno": "1", "news_ofer_entp_code": "F", "data_dt": "20260825",
                  "data_tm": "143012", "hts_pbnt_titl_cntt": "공시 제목", "dorg": "공시"}]
            )
        )

        body = client.get("/api/news/stock/005930").json()

        assert body["code"] == "SUCCESS"
        assert body["status"] == 200
        assert body["data"] == [
            {
                "seqNo": "1",
                "title": "공시 제목",
                "source": "공시",
                "disclosure": True,
                "publishedAt": "2026-08-25T14:30:12",
            }
        ]

    @respx.mock
    def test_거래소를_주면_해외_뉴스를_조회한다(self, respx_mock, 토큰_발급, client):
        해외 = respx_mock.get(OVERSEAS_URL).mock(
            return_value=httpx.Response(200, json={"rt_cd": "0", "outblock1": []})
        )
        국내 = respx_mock.get(DOMESTIC_URL).mock(return_value=국내_응답([]))

        client.get("/api/news/stock/AAPL", params={"exchange": "NAS"})

        assert 해외.called
        assert not 국내.called


class Test캐시:
    @respx.mock
    def test_같은_종목을_다시_조회하면_KIS를_다시_부르지_않는다(self, respx_mock, 토큰_발급):
        from backend.news.application import stock_news

        route = respx_mock.get(DOMESTIC_URL).mock(
            return_value=국내_응답(
                [{"cntt_usiq_srno": "1", "data_dt": "20260825", "data_tm": "090000",
                  "hts_pbnt_titl_cntt": "제목"}]
            )
        )

        stock_news("005930")
        stock_news("005930")

        assert route.call_count == 1

    @respx.mock
    def test_빈_결과는_캐시하지_않아_다음_조회에서_다시_시도한다(self, respx_mock, 토큰_발급):
        from backend.news.application import stock_news

        route = respx_mock.get(DOMESTIC_URL).mock(return_value=국내_응답([]))

        stock_news("005930")
        stock_news("005930")

        assert route.call_count == 2


def test_해외_결과는_국내_캐시를_덮어쓰지_않는다():
    """같은 `stockNews` 캐시를 쓰지만 키 모양이 달라 서로 간섭하지 않는다."""
    from backend.library import cache

    cache.clear_all()
    스토어 = cache._caches["stockNews"]
    스토어[("005930",)] = [
        StockNews("1", "국내", "한경", "", __import__("datetime").datetime(2026, 8, 25))
    ]
    스토어[("NAS", "AAPL")] = []

    assert 스토어[("005930",)][0].title == "국내"
