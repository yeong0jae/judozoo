"""로깅 설정.

Kotlin의 `logback-spring.xml`과 동일하게 **콘솔 평문**이다. 구조화 로깅(JSON)은
쓰지 않는다 — Alloy가 stdout을 그대로 수집하고, 단일 인스턴스라 로그 상관관계를
추적할 대상이 없다.
"""

import logging
import sys

_FORMAT = "%(asctime)s %(levelname)-5s [%(threadName)s] %(name)s : %(message)s"
_DATE_FORMAT = "%Y-%m-%d %H:%M:%S"


def configure_logging(app_package: str = "backend", root_level: int = logging.INFO) -> None:
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(logging.Formatter(_FORMAT, datefmt=_DATE_FORMAT))

    root = logging.getLogger()
    root.handlers.clear()
    root.addHandler(handler)
    root.setLevel(root_level)

    # 애플리케이션 패키지만 DEBUG — logback의 `<logger name="at.backend" level="DEBUG"/>`
    logging.getLogger(app_package).setLevel(logging.DEBUG)
