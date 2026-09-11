"""종목 카탈로그 갱신 스케줄."""

import logging

from apscheduler.schedulers.base import BaseScheduler
from apscheduler.triggers.cron import CronTrigger

from backend.library.time import KST, today
from backend.stock import application

log = logging.getLogger(__name__)


def refresh_catalog() -> None:
    application.refresh(today())


def register(scheduler: BaseScheduler) -> None:
    """매일 개장 전 1회. 기동 시 적재는 앱 lifespan이 따로 부른다."""
    scheduler.add_job(
        refresh_catalog,
        CronTrigger(hour=8, minute=30, timezone=KST),
        id="stock-catalog-refresh",
        replace_existing=True,
    )
