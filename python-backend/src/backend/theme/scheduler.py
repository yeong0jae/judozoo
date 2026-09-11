"""테마 캡처 스케줄 — 정규장 마감(15:40)과 NXT 애프터마켓 마감(20:00) 두 번.

20:00 캡처가 확장세션까지 반영해 그날 스냅샷을 교체한다.
"""

import logging

from apscheduler.schedulers.base import BaseScheduler
from apscheduler.triggers.cron import CronTrigger

from backend.library.db import get_session_factory
from backend.library.time import KST
from backend.market import calendar
from backend.theme import application

log = logging.getLogger(__name__)


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
    for hour, minute, job_id in ((15, 40, "theme-capture-close"), (20, 0, "theme-capture-after")):
        scheduler.add_job(
            run_capture,
            CronTrigger(hour=hour, minute=minute, timezone=KST),
            id=job_id,
            replace_existing=True,
        )
