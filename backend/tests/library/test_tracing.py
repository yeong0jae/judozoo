import json
import logging

import pytest

from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from backend.library import tracing
from backend.library.logging_config import JsonFormatter


_수집기 = InMemorySpanExporter()


@pytest.fixture(scope="module", autouse=True)
def 트레이서():
    """**provider는 프로세스당 한 번만 세워진다.** 두 번째 `set_tracer_provider`는 조용히
    무시되므로, 테스트마다 새로 만들면 두 번째부터 엉뚱한 exporter를 보게 된다.
    한 번만 세우고 아래에서 비운다."""
    provider = TracerProvider()
    provider.add_span_processor(SimpleSpanProcessor(_수집기))
    trace.set_tracer_provider(provider)
    tracing._tracer = trace.get_tracer("test")
    yield


@pytest.fixture(autouse=True)
def 수집기() -> InMemorySpanExporter:
    _수집기.clear()
    return _수집기


class Test잡_스팬:
    def test_잡_하나가_스팬_하나로_열린다(self, 수집기):
        @tracing.traced_job("테스트-폴러")
        def 폴러():
            pass

        폴러()

        spans = 수집기.get_finished_spans()
        assert len(spans) == 1
        assert spans[0].name == "job 테스트-폴러"
        assert spans[0].attributes["scheduler.job_id"] == "테스트-폴러"

    def test_잡_안의_호출이_자식으로_붙는다(self, 수집기):
        """이 작업의 목적 — 브로커 호출이 어느 잡 실행에 속하는지 이어 보는 것."""
        @tracing.traced_job("테스트-폴러")
        def 폴러():
            with trace.get_tracer("test").start_as_current_span("브로커 호출"):
                pass

        폴러()

        spans = {s.name: s for s in 수집기.get_finished_spans()}
        부모 = spans["job 테스트-폴러"]
        자식 = spans["브로커 호출"]
        assert 자식.parent.span_id == 부모.context.span_id

    def test_예외가_밖으로_나가면_스팬에_남는다(self, 수집기):
        @tracing.traced_job("테스트-폴러")
        def 폴러():
            raise ValueError("깨짐")

        try:
            폴러()
        except ValueError:
            pass

        span = 수집기.get_finished_spans()[0]
        assert span.status.status_code.name == "ERROR"
        assert span.events, "record_exception이 이벤트로 남아야 한다"


class Test로그_트레이스_연결:
    def test_스팬_안에서_찍은_로그에_trace_id가_실린다(self, 트레이서):
        record = logging.LogRecord("t", logging.INFO, "f", 1, "메시지", (), None)

        with trace.get_tracer("test").start_as_current_span("요청"):
            payload = json.loads(JsonFormatter().format(record))

        assert len(payload["trace_id"]) == 32

    def test_스팬_밖_로그에는_trace_id가_없다(self):
        """기동 로그처럼 스팬이 없는 줄이 있다. 항상 있다고 가정하면 그 줄들이 사라진다."""
        record = logging.LogRecord("t", logging.INFO, "f", 1, "기동", (), None)

        payload = json.loads(JsonFormatter().format(record))

        assert "trace_id" not in payload
