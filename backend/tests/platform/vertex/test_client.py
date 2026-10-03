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
    monkeypatch.setattr(vertex, "_token", lambda refresh=False: "test-token")
    monkeypatch.setattr(vertex, "_wait", lambda seconds: None)
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

        with pytest.raises(VertexError, match="JSON") as e:
            structure("정리해", {"type": "OBJECT"})
        assert e.value.kind == "bad_answer"

    @respx.mock
    def test_객체가_아닌_JSON도_실패로_본다(self, respx_mock):
        respx_mock.post(URL).mock(return_value=답("[1, 2]"))

        with pytest.raises(VertexError, match="객체"):
            structure("정리해", {"type": "OBJECT"})


class Test실패:
    @respx.mock
    def test_한도_초과는_잠깐_기다렸다_두_번_더_묻는다(self, respx_mock):
        route = respx_mock.post(URL).mock(side_effect=[
            httpx.Response(429, json={"error": {"message": "quota"}}),
            httpx.Response(503, text="busy"),
            답("이제 된다"),
        ])

        assert ground("왜").text == "이제 된다"
        assert route.call_count == 3

    @respx.mock
    def test_두_번_더_물어도_한도_초과면_일시_장애로_올린다(self, respx_mock):
        route = respx_mock.post(URL).mock(return_value=httpx.Response(429, json={"error": {"message": "quota"}}))

        with pytest.raises(VertexError, match="429") as e:
            ground("왜")
        assert e.value.kind == "unavailable"
        assert route.call_count == 3

    @respx.mock
    def test_다시_묻기_전_기다림에_지터를_섞는다(self, respx_mock, monkeypatch):
        waits: list[float] = []
        monkeypatch.setattr(vertex, "_wait", waits.append)
        respx_mock.post(URL).mock(return_value=httpx.Response(500, text="boom"))

        with pytest.raises(VertexError):
            ground("왜")
        assert 1.0 <= waits[0] <= 3.0 and 2.5 <= waits[1] <= 7.5

    @respx.mock
    def test_시간_초과는_다시_묻지_않고_바로_올린다(self, respx_mock):
        route = respx_mock.post(URL).mock(side_effect=httpx.ReadTimeout("느림"))

        with pytest.raises(VertexError, match="ReadTimeout") as e:
            ground("왜")
        assert e.value.kind == "timeout"
        assert route.call_count == 1

    @pytest.mark.parametrize("status", [400, 403, 404])
    @respx.mock
    def test_설정이_틀린_오류는_다시_묻지_않는다(self, respx_mock, status):
        route = respx_mock.post(URL).mock(return_value=httpx.Response(status, text="no"))

        with pytest.raises(VertexError) as e:
            ground("왜")
        assert e.value.kind == "client_error"
        assert route.call_count == 1

    @respx.mock
    def test_인증이_만료되면_토큰을_새로_받아_한_번_더_묻는다(self, respx_mock, monkeypatch):
        tokens = iter(["old", "new"])
        monkeypatch.setattr(vertex, "_token", lambda refresh=False: next(tokens))
        route = respx_mock.post(URL).mock(side_effect=[httpx.Response(401, text="expired"), 답("답")])

        ground("왜")

        assert route.calls.last.request.headers["authorization"] == "Bearer new"

    @respx.mock
    def test_새_토큰으로도_인증이_안_되면_설정_오류다(self, respx_mock):
        respx_mock.post(URL).mock(return_value=httpx.Response(401, text="expired"))

        with pytest.raises(VertexError) as e:
            ground("왜")
        assert e.value.kind == "client_error"

    @respx.mock
    def test_어느_호출에서_실패했는지_문구에_남긴다(self, respx_mock):
        respx_mock.post(URL).mock(side_effect=httpx.ReadTimeout("느림"))

        with pytest.raises(VertexError, match="^structure "):
            structure("정리해", {"type": "OBJECT"})

    @respx.mock
    def test_안전_차단으로_끝난_답은_쓰지_않는다(self, respx_mock):
        respx_mock.post(URL).mock(return_value=답("", finish="SAFETY"))

        with pytest.raises(VertexError, match="SAFETY") as e:
            ground("왜")
        assert e.value.kind == "bad_answer"

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
