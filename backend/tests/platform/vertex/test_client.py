import httpx
import pytest
import respx

from backend.platform.vertex import client as vertex
from backend.platform.vertex.client import VertexError, ground, structure

URL = (
    "https://aiplatform.googleapis.com/v1/projects/trading-496508/locations/global"
    "/publishers/google/models/gemini-3.8-flash:generateContent"
)


@pytest.fixture(autouse=True)
def 버텍스_초기화(monkeypatch):
    vertex.reset()
    monkeypatch.setattr(vertex, "_token", lambda: "test-token")
    yield
    vertex.reset()


def 답(text: str, finish: str = "STOP", grounding: dict | None = None, usage: dict | None = None) -> httpx.Response:
    candidate: dict = {"content": {"role": "model", "parts": [{"text": text}]}, "finishReason": finish}
    if grounding is not None:
        candidate["groundingMetadata"] = grounding
    return httpx.Response(200, json={
        "candidates": [candidate],
        "usageMetadata": usage or {"promptTokenCount": 120, "candidatesTokenCount": 240, "totalTokenCount": 360},
    })


class Test검색_켠_호출:
    @respx.mock
    def test_설명_문장과_출처_검색어_수를_읽는다(self, respx_mock):
        respx_mock.post(URL).mock(return_value=답(
            "LG이노텍은 美 라이다 업체 지분가치 상승으로 올랐다.",
            grounding={
                "webSearchQueries": ["LG이노텍 2026년 9월 28일", "LG이노텍 급등 이유"],
                "groundingChunks": [
                    {"web": {"domain": "fnnews.com", "title": "fnnews.com", "uri": "https://vertexaisearch.cloud.google.com/grounding-api-redirect/A"}},
                    {"web": {"domain": "daum.net", "title": "daum.net", "uri": "https://vertexaisearch.cloud.google.com/grounding-api-redirect/B"}},
                ],
            },
        ))

        g = ground("왜 올랐나")

        assert g.text.startswith("LG이노텍은")
        assert [(s.domain, s.uri[-1]) for s in g.sources] == [("fnnews.com", "A"), ("daum.net", "B")]
        assert g.usage.search_queries == 2
        assert (g.usage.input_tokens, g.usage.output_tokens) == (120, 240)

    @respx.mock
    def test_출처가_없으면_빈_목록이다(self, respx_mock):
        respx_mock.post(URL).mock(return_value=답("기사로 설명되지 않는다."))

        g = ground("왜 올랐나")

        assert g.sources == [] and g.usage.search_queries == 0

    @respx.mock
    def test_사고_조각은_답_문장에_섞지_않는다(self, respx_mock):
        respx_mock.post(URL).mock(return_value=httpx.Response(200, json={"candidates": [{
            "content": {"parts": [{"text": "생각 중…", "thought": True}, {"text": "답이다."}]}, "finishReason": "STOP",
        }]}))

        assert ground("왜").text == "답이다."


class TestJSON_호출:
    @respx.mock
    def test_JSON_객체를_돌려준다(self, respx_mock):
        respx_mock.post(URL).mock(return_value=답('{"explained": true, "evidence": [1]}'))

        data, usage = structure("정리해", {"type": "OBJECT"})

        assert data == {"explained": True, "evidence": [1]}
        assert usage.search_queries == 0

    @respx.mock
    def test_JSON이_깨지면_실패로_본다(self, respx_mock):
        respx_mock.post(URL).mock(return_value=답('{"explained": tru'))

        with pytest.raises(VertexError, match="JSON"):
            structure("정리해", {"type": "OBJECT"})

    @respx.mock
    def test_객체가_아닌_JSON도_실패로_본다(self, respx_mock):
        respx_mock.post(URL).mock(return_value=답("[1, 2]"))

        with pytest.raises(VertexError, match="객체"):
            structure("정리해", {"type": "OBJECT"})


class Test실패:
    @respx.mock
    def test_한도_초과는_실패로_올린다(self, respx_mock):
        respx_mock.post(URL).mock(return_value=httpx.Response(429, json={"error": {"message": "quota"}}))

        with pytest.raises(VertexError, match="429"):
            ground("왜")

    @respx.mock
    def test_시간_초과도_실패로_올린다(self, respx_mock):
        respx_mock.post(URL).mock(side_effect=httpx.ReadTimeout("느림"))

        with pytest.raises(VertexError, match="ReadTimeout"):
            ground("왜")

    @respx.mock
    def test_안전_차단으로_끝난_답은_쓰지_않는다(self, respx_mock):
        respx_mock.post(URL).mock(return_value=답("", finish="SAFETY"))

        with pytest.raises(VertexError, match="SAFETY"):
            ground("왜")

    @respx.mock
    def test_후보가_없으면_실패다(self, respx_mock):
        respx_mock.post(URL).mock(return_value=httpx.Response(200, json={"promptFeedback": {"blockReason": "OTHER"}}))

        with pytest.raises(VertexError):
            ground("왜")

    @respx.mock
    def test_토큰을_Authorization_헤더로_보낸다(self, respx_mock):
        route = respx_mock.post(URL).mock(return_value=답("답"))

        ground("왜")

        assert route.calls.last.request.headers["authorization"] == "Bearer test-token"
