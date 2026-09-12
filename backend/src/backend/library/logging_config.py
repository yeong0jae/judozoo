"""로깅 설정.

Kotlin의 `logback-spring.xml`과 동일하게 **콘솔 평문**이다. 구조화 로깅(JSON)은
쓰지 않는다 — Alloy가 stdout을 그대로 수집하고, 단일 인스턴스라 로그 상관관계를
추적할 대상이 없다.
"""

import logging
import sys

from backend.library.exception import BrokerTokenUnavailable

_FORMAT = "%(asctime)s %(levelname)-5s [%(threadName)s] %(name)s : %(message)s"
_DATE_FORMAT = "%Y-%m-%d %H:%M:%S"


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


def configure_logging(app_package: str = "backend", root_level: int = logging.INFO) -> None:
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(logging.Formatter(_FORMAT, datefmt=_DATE_FORMAT))
    # 핸들러에 달아 uvicorn·starlette 로거까지 함께 덮는다.
    handler.addFilter(QuietExpectedFailures())

    root = logging.getLogger()
    root.handlers.clear()
    root.addHandler(handler)
    root.setLevel(root_level)

    # 애플리케이션 패키지만 DEBUG — logback의 `<logger name="at.backend" level="DEBUG"/>`
    logging.getLogger(app_package).setLevel(logging.DEBUG)
