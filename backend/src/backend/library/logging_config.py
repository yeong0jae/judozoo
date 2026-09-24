"""로깅 설정.

운영은 **JSON 한 줄**, 로컬은 사람이 읽는 평문이다 (`LOG_FORMAT`).

JSON으로 바꾼 이유는 Loki에서 `| json | level="ERROR"` 처럼 **필드로 거르기** 위해서다.
평문일 때는 정규식으로 긁어야 했고, 무엇보다 **uvicorn 액세스 로그가 우리 포맷을 따르지
않아** 한 스트림에 두 모양이 섞여 있었다 — 아래 `_absorb_uvicorn_loggers`가 그걸 없앤다.

로컬까지 JSON으로 하지 않는 이유는 `docker logs`로 눈으로 읽을 때 더 나쁘기 때문이다.
"""

import json
import logging
import sys
from contextvars import ContextVar
from datetime import datetime

from backend.library.exception import BrokerTokenUnavailable
from backend.library.tracing import current_trace_id

_FORMAT = "%(asctime)s %(levelname)-5s [%(threadName)s] %(name)s : %(message)s"
_DATE_FORMAT = "%Y-%m-%d %H:%M:%S"

# LogRecord가 기본으로 갖는 속성들. `extra=`로 넘어온 것만 골라 싣기 위해 쓴다.
# 로그인한 사용자를 요청 동안 들고 있는다. 로그 한 줄마다 누구의 요청이었는지 남기기 위한 것 —
# 이게 있어야 "이 사용자가 무엇을 보고 갔나"를 로그에서 이어 볼 수 있다.
#
# uvicorn이 요청마다 태스크를 새로 띄우고 ContextVar는 태스크별로 복사되므로 요청끼리 섞이지 않는다.
# **되돌리지 않는다** — 응답을 내보내며 찍히는 uvicorn 액세스 로그가 미들웨어가 끝난 뒤에 나와서,
# 거기서 되돌리면 정작 그 줄에 사용자가 안 실린다. 미들웨어가 요청마다 값을 다시 심고
# 비로그인은 None으로 덮으므로 남는 값도 없다.
_user: ContextVar[int | None] = ContextVar("log_user", default=None)


def bind_user(user_id: int | None) -> None:
    """요청을 시작하며 심는다. 비로그인이면 None을 심어 이전 값을 지운다."""
    _user.set(user_id)


def current_user() -> int | None:
    return _user.get()


# 어느 요청이었는지. 트레이스백만으로는 URL을 알 수 없어 라우터 prefix를 코드에서 역산해야 했다.
# 사용자와 같은 수명·같은 이유로 여기 둔다.
#
# **쿼리스트링은 싣지 않는다.** 구글 로그인 콜백이 authorization code를 쿼리로 받는데,
# 그게 Loki에 평문으로 남는다. 파라미터가 필요하면 허용목록으로 골라 따로 싣는다.
_request: ContextVar[str | None] = ContextVar("log_request", default=None)


def bind_request(method: str | None, path: str | None) -> None:
    """요청을 시작하며 심는다. 둘 중 하나라도 없으면 None을 심어 이전 값을 지운다."""
    _request.set(f"{method} {path}" if method and path else None)


def current_request() -> str | None:
    return _request.get()


_RESERVED = frozenset(vars(logging.LogRecord("", 0, "", 0, "", (), None))) | {
    "message",
    "asctime",
    "taskName",
}


class JsonFormatter(logging.Formatter):
    """한 줄 = JSON 객체 하나. Loki의 `| json` 파서가 그대로 읽는다.

    `ts`는 오프셋을 붙인 ISO8601(`+09:00`)이다. 컨테이너 TZ가 KST라 벽시계가 그대로
    찍히는데, 오프셋이 없으면 Loki가 UTC로 읽어 9시간이 어긋난다.
    """

    def format(self, record: logging.LogRecord) -> str:
        payload = {
            "ts": datetime.fromtimestamp(record.created).astimezone().isoformat(timespec="milliseconds"),
            "level": record.levelname,
            "logger": record.name,
            "msg": record.getMessage(),
            "thread": record.threadName,
        }
        # Loki에서 이 필드를 눌러 Tempo로 점프한다(021 §4). Grafana derivedFields가
        # 이름으로 건다. **없는 게 정상인 로그가 있다** — 기동·스케줄러 밖 로그에는
        # 스팬이 없다. 항상 있다고 가정하고 쿼리를 짜면 그 줄들이 사라진다.
        trace_id = current_trace_id()
        if trace_id:
            payload["trace_id"] = trace_id
        # 로그인한 사용자. **없는 게 정상인 로그가 많다** — 비로그인 요청과 스케줄러·기동
        # 로그에는 사용자가 없다. trace_id와 같은 사정이다.
        user = current_user()
        if user is not None:
            payload["user"] = user
        # 요청 경로. 사용자와 같이 **없는 게 정상인 로그가 많다**.
        request = current_request()
        if request:
            payload["request"] = request
        if record.exc_info:
            payload["exc"] = self.formatException(record.exc_info)
        # logger.info("...", extra={"user": "..."}) 로 넘긴 값만 실린다.
        payload.update({k: v for k, v in record.__dict__.items() if k not in _RESERVED})
        # ensure_ascii=False — 한글 메시지가 \uXXXX로 깨지면 Grafana에서 못 읽는다.
        return json.dumps(payload, ensure_ascii=False, default=str)


def _absorb_uvicorn_loggers() -> None:
    """uvicorn 로거를 루트로 흘려보낸다.

    uvicorn은 기동 시 `uvicorn`·`uvicorn.error`·`uvicorn.access`에 **자기 핸들러를 달고
    propagate를 끈다.** 루트 핸들러만 갈면 액세스 로그는 그대로 남아
    `INFO:     127.0.0.1:59032 - "GET /health HTTP/1.1" 200 OK` 같은 딴 모양이 섞인다.
    """
    for name in ("uvicorn", "uvicorn.error", "uvicorn.access"):
        logger = logging.getLogger(name)
        logger.handlers.clear()
        logger.propagate = True


class QuietExpectedFailures(logging.Filter):
    """예상된 실패에는 스택트레이스를 붙이지 않는다.

    브로커 토큰 백오프는 버그가 아니라 **상태**다. 그런데 화면이 5초마다 폴링하고
    폴러가 10초마다 돌아서, 트레이스를 찍으면 로그가 트레이스로 뒤덮인다 —
    2026-09-12에 한 시간 만에 28,000줄이 쌓였고 그 대부분이 같은 트레이스였다.

    호출처(클라이언트 8곳 + 전역 핸들러 + uvicorn)를 각각 고치는 대신 여기서 한 번 막는다.
    메시지는 그대로 남으므로 "무슨 일이 있었는지"는 잃지 않는다.
    """

    def filter(self, record: logging.LogRecord) -> bool:
        if not record.exc_info or not isinstance(record.exc_info[1], BrokerTokenUnavailable):
            return True
        exc = record.exc_info[1]
        # 사유는 메시지 꼬리에 붙여 살린다. 예외 문구에 %가 섞여도 깨지지 않게 **인자로** 넘긴다.
        if record.args:
            record.msg = f"{record.msg} — %s"
            record.args = (*record.args, exc)
        else:
            record.msg = f"{record.msg} — %s"
            record.args = (exc,)
        record.exc_info = None
        record.exc_text = None
        return True


class QuietProbeAccessLogs(logging.Filter):
    """정상 응답한 프로브 액세스 로그는 버린다.

    `/health`는 docker healthcheck가 10초마다, `/metrics`는 alloy가 30초마다 친다.
    합쳐서 하루 11,000줄이 넘는데 전부 200이고, 사람이 읽을 일이 있는 줄은 하나도 없다.
    Loki에서 진짜 요청을 찾으려면 이 둘을 눈으로 걸러내야 했다.

    **4xx·5xx는 남긴다.** 프로브가 실패하는 순간은 오히려 봐야 하는 순간이다 —
    healthcheck가 6번 연속 실패하면 배포 스크립트가 롤백을 건다.

    쿼리스트링이 붙은 `/health?x=1`은 경로가 달라져 그대로 찍힌다. 두 경로 모두
    쿼리를 받지 않으니 지금은 문제가 없고, 받게 되는 날 여기를 같이 고쳐야 한다.
    """

    _PATHS = frozenset({"/health", "/metrics"})

    def filter(self, record: logging.LogRecord) -> bool:
        if record.name != "uvicorn.access":
            return True
        # uvicorn은 `'%s - "%s %s HTTP/%s" %d'` 에 (주소, 메서드, 경로, 버전, 상태)를 넘긴다.
        # 이 모양이 바뀌면 조용히 거르기를 멈출 뿐, 로그를 잃지는 않게 둔다.
        args = record.args
        if not isinstance(args, tuple) or len(args) != 5:
            return True
        _, _, path, _, status = args
        return not (path in self._PATHS and isinstance(status, int) and status < 400)


def configure_logging(
    app_package: str = "backend",
    root_level: int = logging.INFO,
    log_format: str = "text",
) -> None:
    formatter: logging.Formatter = (
        JsonFormatter() if log_format == "json" else logging.Formatter(_FORMAT, datefmt=_DATE_FORMAT)
    )
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(formatter)
    # 핸들러에 달아 uvicorn·starlette 로거까지 함께 덮는다.
    handler.addFilter(QuietExpectedFailures())
    handler.addFilter(QuietProbeAccessLogs())

    root = logging.getLogger()
    root.handlers.clear()
    root.addHandler(handler)
    root.setLevel(root_level)

    _absorb_uvicorn_loggers()

    # APScheduler는 작업마다 시작·성공을 INFO로 두 줄씩 남긴다 — 10초 작업 하나만으로 하루 17,000줄이다.
    # 실행 횟수는 메트릭이 센다. 예외(ERROR)와 누락 실행(WARNING)은 그대로 남는다.
    logging.getLogger("apscheduler.executors").setLevel(logging.WARNING)

    # 애플리케이션 패키지만 DEBUG — logback의 `<logger name="at.backend" level="DEBUG"/>`
    logging.getLogger(app_package).setLevel(logging.DEBUG)
