import httpx
import pytest
import respx

from backend.platform.vertex import articles
from backend.platform.vertex.articles import resolve

REDIRECT = "https://vertexaisearch.cloud.google.com/grounding-api-redirect/AAA"
ARTICLE = "https://www.fnnews.com/news/202609280920187609"


@pytest.fixture(autouse=True)
def 초기화():
    articles.reset()
    yield
    articles.reset()


def 페이지(html: str, charset: str = "utf-8", header_charset: bool = True) -> httpx.Response:
    headers = {"content-type": f"text/html; charset={charset}" if header_charset else "text/html"}
    return httpx.Response(200, content=html.encode(charset), headers=headers)


class Test출처_따라가기:
    @respx.mock
    def test_전달_주소를_따라가_실제_URL과_제목을_얻는다(self, respx_mock):
        respx_mock.get(REDIRECT).mock(return_value=httpx.Response(302, headers={"location": ARTICLE}))
        respx_mock.get(ARTICLE).mock(return_value=페이지(
            "<html><head><title>LG이노텍, 장 초반 10% 강세 - 파이낸셜뉴스</title></head></html>"
        ))

        a = resolve(REDIRECT)

        assert a.url == ARTICLE
        assert a.title == "LG이노텍, 장 초반 10% 강세 - 파이낸셜뉴스"
        assert a.site is None

    @respx.mock
    def test_og_title이_있으면_그것을_쓴다(self, respx_mock):
        respx_mock.get(REDIRECT).mock(return_value=httpx.Response(302, headers={"location": ARTICLE}))
        respx_mock.get(ARTICLE).mock(return_value=페이지(
            '<head><meta property="og:title" content="LG이노텍, 장 초반 10% 강세">'
            "<title>LG이노텍, 장 초반 10% 강세 : 파이낸셜뉴스 - 증권</title></head>"
        ))

        assert resolve(REDIRECT).title == "LG이노텍, 장 초반 10% 강세"

    @respx.mock
    def test_매체명은_og_site_name에서_읽는다(self, respx_mock):
        respx_mock.get(REDIRECT).mock(return_value=httpx.Response(302, headers={"location": ARTICLE}))
        respx_mock.get(ARTICLE).mock(return_value=페이지(
            '<head><meta property="og:site_name" content="파이낸셜뉴스"><title>LG이노텍, 장 초반 10% 강세</title></head>'
        ))

        assert resolve(REDIRECT).site == "파이낸셜뉴스"

    @respx.mock
    def test_헤더에_없어도_meta_charset으로_EUC_KR을_읽는다(self, respx_mock):
        respx_mock.get(REDIRECT).mock(return_value=httpx.Response(302, headers={"location": ARTICLE}))
        respx_mock.get(ARTICLE).mock(return_value=페이지(
            '<head><meta charset="euc-kr"><title>대우건설, 용인 국가산단 낙찰</title></head>',
            charset="euc-kr", header_charset=False,
        ))

        assert resolve(REDIRECT).title == "대우건설, 용인 국가산단 낙찰"

    @respx.mock
    def test_기사_쪽에서_한번_더_넘기면_마지막_주소를_남긴다(self, respx_mock):
        final = "https://m.fnnews.com/news/202609280920187609"
        respx_mock.get(REDIRECT).mock(return_value=httpx.Response(302, headers={"location": ARTICLE}))
        respx_mock.get(ARTICLE).mock(return_value=httpx.Response(301, headers={"location": final}))
        respx_mock.get(final).mock(return_value=페이지("<title>제목</title>"))

        assert resolve(REDIRECT).url == final


class Test못_따라가면_뺀다:
    @respx.mock
    def test_전달_주소가_넘기지_않으면_없다(self, respx_mock):
        respx_mock.get(REDIRECT).mock(return_value=httpx.Response(404))

        assert resolve(REDIRECT) is None

    @respx.mock
    def test_기사가_없어졌으면_없다(self, respx_mock):
        respx_mock.get(REDIRECT).mock(return_value=httpx.Response(302, headers={"location": ARTICLE}))
        respx_mock.get(ARTICLE).mock(return_value=httpx.Response(404, text="없음"))

        assert resolve(REDIRECT) is None

    @respx.mock
    def test_제목이_없는_페이지는_없다(self, respx_mock):
        respx_mock.get(REDIRECT).mock(return_value=httpx.Response(302, headers={"location": ARTICLE}))
        respx_mock.get(ARTICLE).mock(return_value=페이지("<html><body>본문</body></html>"))

        assert resolve(REDIRECT) is None

    @respx.mock
    def test_기사_사이트가_응답하지_않으면_없다(self, respx_mock):
        respx_mock.get(REDIRECT).mock(return_value=httpx.Response(302, headers={"location": ARTICLE}))
        respx_mock.get(ARTICLE).mock(side_effect=httpx.ConnectTimeout("느림"))

        assert resolve(REDIRECT) is None
