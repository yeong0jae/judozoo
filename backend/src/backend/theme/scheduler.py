"""테마 캡처 스케줄 — 장중 4회(09·12·15·18시)와 NXT 애프터마켓 마감(20시).

`capture`는 그날 것을 지우고 다시 넣는다. 여러 번 돌아도 최신 한 벌만 남으므로,
자주 찍을수록 화면이 보는 시점이 최근이 된다. 20:00 캡처가 확장세션까지 반영한 최종본이다.
"""

import logging

from apscheduler.schedulers.base import BaseScheduler
from apscheduler.triggers.cron import CronTrigger

from backend.library.db import get_session_factory
from backend.library.time import KST
from backend.market import calendar
from backend.theme import application

log = logging.getLogger(__name__)

# KST 정시. 20시는 NXT 애프터마켓 마감 직후다.
_CAPTURE_HOURS = (9, 12, 15, 18, 20)


def run_capture() -> None:
    if calendar.is_holiday(calendar.Region.KR):
        log.info("휴장일 — 테마 캡처 스킵")
        return
    try:
        with get_session_factory()() as session:
            application.capture(session)
    except Exception:
        log.warning("테마 캡처 실패", exc_info=True)


def register(scheduler: BaseScheduler) -> None:
    for hour in _CAPTURE_HOURS:
        scheduler.add_job(
            run_capture,
            CronTrigger(hour=hour, minute=0, timezone=KST),
            id=f"theme-capture-{hour:02d}",
            replace_existing=True,
        )
