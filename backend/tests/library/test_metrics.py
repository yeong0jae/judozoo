import httpx
import pytest
import respx
from anyio import CapacityLimiter

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


def 게이지값(gauge) -> float:
    """`set_function`으로 건 Gauge는 스크레이프 시점에 계산된다 — `_value`에는 안 담긴다."""
    return list(gauge.collect())[0].samples[0].value


class Test스레드풀_계측:
    def test_정원과_사용량과_대기를_그대로_비춘다(self):
        """sync 엔드포인트가 리미터 대기로 슬롯을 물고 잠들면 CPU 그래프에는 안 보인다.
        그 구간을 보는 창이라, 세 값이 limiter 상태를 그대로 따라와야 한다."""
        limiter = CapacityLimiter(40)
        metrics.track_thread_pool(limiter)

        assert 게이지값(metrics.THREAD_POOL_TOTAL) == 40
        assert 게이지값(metrics.THREAD_POOL_BORROWED) == 0
        assert 게이지값(metrics.THREAD_POOL_WAITING) == 0

    def test_정원을_바꾸면_따라간다(self):
        """스레드풀을 늘려도 리미터 통과량은 그대로라, 둘을 나란히 봐야 판단이 선다."""
        limiter = CapacityLimiter(40)
        metrics.track_thread_pool(limiter)

        limiter.total_tokens = 100

        assert 게이지값(metrics.THREAD_POOL_TOTAL) == 100
