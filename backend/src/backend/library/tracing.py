"""OpenTelemetry 트레이스 설정과 잡 스팬.

[021](../../../../docs/tasks/021-tempo-트레이스.md). 요청 경로는 자동 계측(FastAPI·httpx·
SQLAlchemy)이 덮는다. 여기서 직접 쓰는 것은 **스케줄러 잡 경계** 하나다 —
OTel에 APScheduler 계측이 없어서, 이걸 안 열면 브로커 호출 스팬이 부모 없이 흩어진다.
그러면 "이 잡 실행이 어디서 멈췄나"라는 이 작업의 목적 자체를 못 본다.

`OTLP_ENDPOINT`가 비어 있으면 아무것도 켜지 않는다. 로컬·테스트 기본값이 그렇다.
"""

import logging
from collections.abc import Callable
from functools import wraps
from typing import TypeVar

from opentelemetry import trace
from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.trace import Status, StatusCode

log = logging.getLogger(__name__)

_tracer = trace.get_tracer("backend")

# 헬스체크는 10초마다, 화면 폴링은 5초마다 온다. 넣어두면 트레이스가 이걸로 뒤덮인다
# (020에서 메트릭에 한 것과 같은 이유).
_EXCLUDED_URLS = "health,health/db,metrics"


def setup(endpoint: str, service_name: str, engine) -> None:
    """엔드포인트가 있을 때만 켠다. 기동을 막지 않는다 — 트레이스가 없다고 앱이 멈출 이유는 없다."""
    if not endpoint:
        log.info("트레이스 비활성화 (OTLP_ENDPOINT 없음)")
        return

    provider = TracerProvider(resource=Resource.create({"service.name": service_name}))
    # Batch — 스팬마다 전송하면 폴러가 10초마다 도는 이 앱에서 부담이 된다.
    provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter(endpoint=endpoint, insecure=True)))
    trace.set_tracer_provider(provider)

    # 계측은 import를 늦춘다 — 꺼져 있을 때 굳이 끌어오지 않는다.
    from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentor
    from opentelemetry.instrumentation.sqlalchemy import SQLAlchemyInstrumentor

    # httpx 계측은 커스텀 transport 체인 **위**를 감싼다. 즉 스팬에 리미터 대기가 포함되고,
    # 020의 http_client_request_duration_seconds와 같은 의미가 된다(서로 대조가 된다).
    #
    # 이름을 손보는 이유 — 기본 스팬 이름이 메서드뿐(`POST`)이라, 잡 하나에 70개가 달리면
    # 워터폴에서 **어느 호출이 느린지 구분이 안 된다**. URL은 속성에 있지만 한 줄씩 열어봐야 한다.
    HTTPXClientInstrumentor().instrument(request_hook=_name_client_span)
    SQLAlchemyInstrumentor().instrument(engine=engine)
    log.info("트레이스 활성화 — %s", endpoint)


def _name_client_span(span, request) -> None:
    """외부 호출 스팬을 `POST /api/dostk/rkinfo` 모양으로 바꾼다.

    메트릭의 `endpoint` 라벨과 달리 **경로를 정규화하지 않는다.** 트레이스는 한 건을 보는
    물건이라 종목코드가 그대로 보이는 편이 낫고, 스팬 이름에는 카디널리티 문제가 없다.
    """
    try:
        method = request.method.decode() if isinstance(request.method, bytes) else str(request.method)
        path = request.url.path if hasattr(request.url, "path") else ""
        if isinstance(path, bytes):
            path = path.decode()
        span.update_name(f"{method} {path}" if path else method)
    except Exception:
        # 이름이 안 예뻐지는 것보다 호출이 깨지는 게 훨씬 나쁘다.
        pass


def instrument_app(app) -> None:
    """FastAPI 계측. 미들웨어를 더하므로 앱 조립이 끝난 뒤 부른다."""
    if not _is_on():
        return
    from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor

    FastAPIInstrumentor.instrument_app(app, excluded_urls=_EXCLUDED_URLS)


def _is_on() -> bool:
    """SDK가 설치됐는지가 아니라 **우리가 켰는지**를 본다.

    OTel 기본 provider는 아무것도 기록하지 않는 NoOp이다. setup()을 건너뛴 상태에서
    계측을 붙여도 동작은 하지만, 붙이지 않는 편이 명확하다.
    """
    return isinstance(trace.get_tracer_provider(), TracerProvider)


F = TypeVar("F", bound=Callable[..., None])


def traced_job(job_id: str) -> Callable[[F], F]:
    """스케줄러 잡 하나를 스팬 하나로 연다.

    **`metrics.on_job_event` 리스너와 역할이 다르다.** 리스너는 잡이 끝난 뒤 집계하고,
    이 데코레이터는 잡이 도는 **동안** 스팬을 열어 브로커·DB 호출이 자식으로 붙게 한다.
    둘 다 필요하다.

    폴러는 예외를 삼키고 정상 리턴하므로(020에서 배운 것) 스팬만으로는 실패가 안 보인다.
    삼키기 전에 여기서 기록하지는 못한다 — 대신 잡이 예외를 밖으로 내보내는 경우만 잡는다.
    삼킨 실패는 `scheduler_job_errors_total`이 맡는다.
    """

    def decorate(fn: F) -> F:
        @wraps(fn)
        def wrapper(*args, **kwargs):
            with _tracer.start_as_current_span(f"job {job_id}") as span:
                span.set_attribute("scheduler.job_id", job_id)
                try:
                    return fn(*args, **kwargs)
                except Exception as exc:
                    span.record_exception(exc)
                    span.set_status(Status(StatusCode.ERROR))
                    raise

        return wrapper  # type: ignore[return-value]

    return decorate


def current_trace_id() -> str | None:
    """로그에 실을 trace_id. 유효한 스팬이 없으면 None.

    **없는 게 정상인 경우가 있다** — 기동 로그, 스케줄러 밖 로그에는 스팬이 없다.
    """
    ctx = trace.get_current_span().get_span_context()
    return format(ctx.trace_id, "032x") if ctx.is_valid else None
