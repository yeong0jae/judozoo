"""Prometheus 메트릭 정의와 계측 도구.

웹 요청 메트릭은 `main.py`의 Instrumentator가 만든다. 여기 있는 것은 그것이 못 보는
넷이다 — **스케줄러 잡**, **외부 HTTP 호출**, **스레드풀**, **레이트 리미터 대기**.

이 모듈은 vendor를 모른다. `endpoint` 라벨을 어떻게 뽑을지는 호출측이 정한다
(키움은 `api-id` 헤더, 토스·야후는 경로에 심볼이 박혀 있어 정규화가 필요하다).
"""

import logging
import time
from collections.abc import Callable

import httpx
from anyio import CapacityLimiter
from apscheduler.events import EVENT_JOB_MISSED, JobExecutionEvent, JobSubmissionEvent
from prometheus_client import Counter, Gauge, Histogram

log = logging.getLogger(__name__)

# **라벨 이름이 `job`이면 안 된다.** `job`·`instance`는 Prometheus가 타깃에 붙이는 예약
# 라벨이라, 스크레이프 시 우리 쪽 `job`이 `exported_job`으로 밀려난다. 그러면
# `sum by (job)`이 전부 스크레이프 job("backend") 한 줄로 뭉쳐 대시보드가 무의미해진다.
SCHEDULER_RUNS = Counter(
    "scheduler_job_runs_total",
    "스케줄러 잡 실행 결과 (APScheduler 기준)",
    ["job_id", "result"],
)

# 잡이 스스로 삼킨 실패. `scheduler_job_runs_total{result="success"}`와 **겹쳐서 올라간다** —
# APScheduler 입장에서는 정상 종료이기 때문이다. 둘을 하나로 합치려면 잡 내부 상태를
# 리스너와 공유해야 하는데, 카운터를 하나 더 두는 쪽이 싸고 뜻이 분명하다.
SCHEDULER_ERRORS = Counter(
    "scheduler_job_errors_total",
    "잡이 내부에서 삼킨 실패 — APScheduler는 성공으로 본다",
    ["job_id"],
)

# 마감 스냅샷 같은 잡은 분 단위로 돈다. 기본 버킷은 상한이 10초라 전부 +Inf에 몰린다.
SCHEDULER_DURATION = Histogram(
    "scheduler_job_duration_seconds",
    "스케줄러 잡 소요 시간",
    ["job_id"],
    buckets=(0.5, 1, 2.5, 5, 10, 30, 60, 300, float("inf")),
)

HTTP_CLIENT_REQUESTS = Counter(
    "http_client_requests_total",
    "외부 HTTP 호출 결과",
    ["vendor", "endpoint", "status"],
)

# 리미터 대기를 포함하므로(아래 MeteredTransport 참고) 20~30초가 정상 범위에 있다.
# 기본 버킷 상한 10초로는 그 구간이 안 보인다. 45·60은 Vertex 타임아웃(60초) 아래를 보려고 둔다 —
# 30에서 끊기면 느려진 건지 타임아웃 직전인지 가를 수 없었다(026 §실패 처리).
HTTP_CLIENT_DURATION = Histogram(
    "http_client_request_duration_seconds",
    "외부 HTTP 응답 시간 — 레이트 리미터 대기 포함",
    ["vendor", "endpoint"],
    buckets=(0.1, 0.25, 0.5, 1, 2, 5, 10, 20, 30, 45, 60, float("inf")),
)

# 리미터 대기. **대부분의 호출은 0초에 가깝다** — 한도에 안 걸리면 토큰이 바로 나온다.
# 그래서 밀리초 단위 버킷이 아래쪽에 촘촘해야 "안 걸림"과 "조금 걸림"이 갈린다.
# 위쪽은 KIS 타임아웃 30초·키움 20초를 덮는다.
RATE_LIMITER_WAIT = Histogram(
    "rate_limiter_wait_seconds",
    "레이트 리미터에서 허가를 얻기까지 기다린 시간",
    ["limiter"],
    buckets=(0.001, 0.01, 0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10, 20, 30, float("inf")),
)

# 허가를 못 얻고 버린 호출. **이 카운터가 움직이면 화면에 빈 칸이 뜬다** —
# 호출자는 예외를 받고 그 자리를 채우지 못한다.
RATE_LIMITER_TIMEOUTS = Counter(
    "rate_limiter_timeouts_total",
    "timeout 안에 허가를 얻지 못해 포기한 횟수",
    ["limiter"],
)

# LLM 사용량(026). 요청 수·지연은 MeteredTransport가 이미 잰다 — 여기는 청구서가 세는 단위다.
# 검색은 월 5,000회 무료라 한도에 얼마나 붙었는지 본다.
LLM_TOKENS = Counter(
    "llm_tokens_total",
    "LLM이 과금 기준으로 센 토큰",
    ["model", "kind"],  # kind: input | output | thoughts
)
LLM_SEARCH_QUERIES = Counter(
    "llm_search_queries_total",
    "그라운딩 검색이 실행한 검색어 수",
    ["model"],
)
LLM_FAILURES = Counter(
    "llm_failures_total",
    "LLM 답을 게시하지 못한 까닭",
    ["model", "kind"],  # kind: timeout | unavailable | client_error | bad_answer
)
# 상태는 시간만 지나도 바뀐다(열림 → 5분 뒤 시험). 스크레이프 때 읽는다 — `insight.application`이 연결한다
LLM_CIRCUIT_STATE = Gauge("llm_circuit_state", "Vertex 서킷 — 0 닫힘 · 1 시험 · 2 열림")

# ── 스레드가 지금 무엇을 하고 있는가 ────────────────────────────────────
#
# `thread_pool_borrowed_threads`는 **몇 개가 쓰이는지**만 알려준다. 40개가 잡혀 있어도
# 그것이 줄서기인지 브로커 응답 대기인지 DB 조회인지 구분되지 않아, 정작 "무엇을 고쳐야
# 하나"에는 답하지 못한다. 아래 둘이 그 안을 가른다.
#
# `MeteredTransport`가 리미터 **바깥**에 감겨 있어 IN_FLIGHT는 대기까지 포함한다.
# 따라서 `in_flight - waiting`이 실제로 소켓에 매달린 수다.
#
# **합이 borrowed와 맞지 않을 수 있다.** 스케줄러 잡은 anyio 풀이 아니라 APScheduler의
# 자체 ThreadPoolExecutor에서 도는데 같은 리미터를 쓴다 — 그쪽 대기도 여기 잡힌다.
# 빼기로 정확한 값을 얻으려 들지 말고, 각 줄을 그 자체로 읽어야 한다.
RATE_LIMITER_WAITING = Gauge(
    "rate_limiter_waiting_threads",
    "지금 리미터에서 허가를 기다리며 잠들어 있는 스레드 수",
    ["limiter"],
)

HTTP_CLIENT_IN_FLIGHT = Gauge(
    "http_client_in_flight_requests",
    "지금 외부 호출에 매달려 있는 스레드 수 — 리미터 대기 포함",
    ["vendor"],
)

# ── 스레드풀 ────────────────────────────────────────────────────────────
#
# sync 엔드포인트는 anyio 기본 스레드풀(40)에서 돈다. 브로커 호출이 전부 sync라
# **리미터 대기가 이 슬롯을 잡은 채로 잠든다** — CPU는 놀고 응답만 밀리는 상태가 되고,
# 자원 그래프(CPU·디스크)로는 아무것도 안 보인다. 그 구간을 보는 유일한 창이다.
#
# Gauge 셋은 `set_function`으로 스크레이프 시점에 읽는다. 폴링 루프를 따로 두면
# 30초 스크레이프 사이의 값을 놓치거나, 반대로 안 쓰는 값을 계속 재게 된다.
THREAD_POOL_TOTAL = Gauge("thread_pool_total_threads", "anyio 기본 스레드풀 정원")
THREAD_POOL_BORROWED = Gauge("thread_pool_borrowed_threads", "사용 중인 스레드 수")

# **이 값이 0을 넘으면 그 순간 요청이 밀리고 있다.** 정원이 다 찼고 뒤에 줄이 섰다는 뜻이라,
# 점유율(borrowed/total)보다 먼저 봐야 할 지표다 — 점유율은 40/40에서 포화를 넘는 순간을
# 더 보여주지 못하지만 이 값은 계속 자란다.
THREAD_POOL_WAITING = Gauge("thread_pool_waiting_tasks", "스레드를 얻지 못해 대기 중인 요청 수")


def track_thread_pool(limiter: CapacityLimiter) -> None:
    """스레드풀 Gauge를 스크레이프에 연결한다.

    **반드시 async 컨텍스트에서 얻은 limiter를 넘겨야 한다.** anyio의
    `current_default_thread_limiter()`는 실행 중인 이벤트 루프를 찾아 값을 돌려주므로
    동기 코드에서 부르면 실패한다. 한 번 잡아 둔 객체의 `.statistics()`는 내부 상태를
    읽기만 해서 스크레이프 스레드에서 불러도 안전하다.
    """
    THREAD_POOL_TOTAL.set_function(lambda: limiter.total_tokens)
    THREAD_POOL_BORROWED.set_function(lambda: limiter.borrowed_tokens)
    THREAD_POOL_WAITING.set_function(lambda: limiter.statistics().tasks_waiting)


# ── 스케줄러 ────────────────────────────────────────────────────────────

# 잡 제출 시각. APScheduler 이벤트에는 소요 시간이 없어서 직접 재야 한다.
# **잡이 자기 자신과 겹치지 않는다는 전제**로 job_id만 키로 쓴다 — 등록된 5개 잡 모두
# `max_instances`를 지정하지 않아 APScheduler 기본값 1이 적용된다.
_started_at: dict[str, float] = {}


def on_job_submitted(event: JobSubmissionEvent) -> None:
    _started_at[event.job_id] = time.monotonic()


def on_job_event(event: JobExecutionEvent) -> None:
    """실행 종료 리스너.

    잡마다 데코레이터를 붙이는 대신 리스너 하나로 처리한다 — 잡을 새로 추가할 때
    계측을 빠뜨릴 데가 없다.
    """
    job = event.job_id
    if event.code == EVENT_JOB_MISSED:
        # 미스파이어는 폴러가 밀리고 있다는 뜻이다. 로그에는 남지 않는다.
        SCHEDULER_RUNS.labels(job_id=job, result="missed").inc()
        _started_at.pop(job, None)
        return

    started = _started_at.pop(job, None)
    if started is not None:
        SCHEDULER_DURATION.labels(job_id=job).observe(time.monotonic() - started)
    SCHEDULER_RUNS.labels(job_id=job, result="error" if event.exception else "success").inc()


def job_failed(job: str) -> None:
    """잡이 예외를 삼키는 자리에서 직접 부른다.

    `leadingstock.scheduler`의 폴러들은 예외를 `log.warning`으로 흘리고 정상 리턴한다.
    그러면 APScheduler는 성공으로 보므로 `on_job_event`가 그 실패를 영영 못 본다.
    """
    SCHEDULER_ERRORS.labels(job_id=job).inc()


# ── 외부 HTTP ───────────────────────────────────────────────────────────


class MeteredTransport(httpx.BaseTransport):
    """외부 호출을 계측하는 transport.

    **event_hook이 아니라 transport인 이유**는 타임아웃·연결 실패를 잡아야 하기 때문이다 —
    hook은 응답이 돌아온 경우만 본다. KIS의 `RateLimitedTransport`와 같은 모양이라
    새로운 개념이 아니다.

    **리미터 바깥에 감는다.** 안쪽에 감으면 브로커 왕복만 재는데, 화면이 느린 이유는
    대개 리미터 대기(KIS 최대 30초·키움 20초)라 정작 원인이 안 보인다. 호출자가
    실제로 겪은 시간을 재고, 대기 시간 분리는 별도 메트릭으로 남겨둔다.

    스트리밍이 아니므로 응답 헤더까지의 시간을 본문 수신 시간으로 간주한다 —
    브로커 응답은 전부 한 번에 읽는 JSON이다.
    """

    def __init__(
        self,
        vendor: str,
        inner: httpx.BaseTransport,
        label_endpoint: Callable[[httpx.Request], str] | None = None,
    ) -> None:
        self._vendor = vendor
        self._inner = inner
        self._label = label_endpoint or (lambda request: request.url.path)

    def handle_request(self, request: httpx.Request) -> httpx.Response:
        endpoint = self._label(request)
        started = time.monotonic()
        # endpoint는 라벨에서 뺀다 — 이 Gauge는 "지금 몇 개가 매달려 있나"를 보는 것이라
        # 엔드포인트별로 쪼개면 대부분 0인 시계열만 늘고 합계는 읽기 어려워진다.
        with HTTP_CLIENT_IN_FLIGHT.labels(self._vendor).track_inprogress():
            try:
                response = self._inner.handle_request(request)
            except Exception as exc:
                # 타임아웃·연결 거부. 예외 타입은 유한한 집합이라 라벨로 안전하다.
                HTTP_CLIENT_REQUESTS.labels(self._vendor, endpoint, type(exc).__name__).inc()
                raise
            finally:
                HTTP_CLIENT_DURATION.labels(self._vendor, endpoint).observe(
                    time.monotonic() - started
                )
        HTTP_CLIENT_REQUESTS.labels(self._vendor, endpoint, str(response.status_code)).inc()
        return response

    def close(self) -> None:
        self._inner.close()
