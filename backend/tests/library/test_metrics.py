import httpx
import pytest
import respx

from backend.library import metrics


def 호출수(vendor: str, endpoint: str, status: str) -> float:
    value = metrics.HTTP_CLIENT_REQUESTS.labels(vendor, endpoint, status)._value.get()
    return value or 0.0


class Test외부_호출_계측:
    @respx.mock
    def test_응답이_오면_상태코드를_라벨로_센다(self):
        respx.get("https://example.test/v1/quote").mock(return_value=httpx.Response(200))
        client = httpx.Client(
            base_url="https://example.test",
            transport=metrics.MeteredTransport("테스트벤더", httpx.HTTPTransport()),
        )

        before = 호출수("테스트벤더", "/v1/quote", "200")
        client.get("/v1/quote")

        assert 호출수("테스트벤더", "/v1/quote", "200") == before + 1

    @respx.mock
    def test_타임아웃도_버리지_않고_예외_이름으로_센다(self):
        """응답이 안 오는 실패야말로 놓치면 안 되는 쪽이다 — event hook으로는 못 잡는다."""
        respx.get("https://example.test/v1/quote").mock(side_effect=httpx.ConnectTimeout)
        client = httpx.Client(
            base_url="https://example.test",
            transport=metrics.MeteredTransport("테스트벤더", httpx.HTTPTransport()),
        )

        before = 호출수("테스트벤더", "/v1/quote", "ConnectTimeout")
        with pytest.raises(httpx.ConnectTimeout):
            client.get("/v1/quote")

        assert 호출수("테스트벤더", "/v1/quote", "ConnectTimeout") == before + 1

    @respx.mock
    def test_엔드포인트_라벨을_호출측이_정한다(self):
        """심볼이 경로에 박힌 vendor는 그대로 두면 종목 수만큼 시계열이 생긴다."""
        respx.get("https://example.test/chart/005930").mock(return_value=httpx.Response(200))
        respx.get("https://example.test/chart/000660").mock(return_value=httpx.Response(200))
        client = httpx.Client(
            base_url="https://example.test",
            transport=metrics.MeteredTransport(
                "테스트벤더", httpx.HTTPTransport(), label_endpoint=lambda _: "/chart/{symbol}"
            ),
        )

        before = 호출수("테스트벤더", "/chart/{symbol}", "200")
        client.get("/chart/005930")
        client.get("/chart/000660")

        assert 호출수("테스트벤더", "/chart/{symbol}", "200") == before + 2


class Test삼켜진_잡_실패:
    def test_잡이_예외를_흘려도_실패로_집계된다(self):
        """폴러는 예외를 log.warning으로 흘리고 정상 리턴한다 — APScheduler는 성공으로 본다."""
        before = metrics.SCHEDULER_ERRORS.labels(job_id="아무-폴러")._value.get() or 0.0

        metrics.job_failed("아무-폴러")

        assert metrics.SCHEDULER_ERRORS.labels(job_id="아무-폴러")._value.get() == before + 1
