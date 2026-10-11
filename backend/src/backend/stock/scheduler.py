"""종목 카탈로그 갱신 스케줄."""

import logging

from apscheduler.schedulers.base import BaseScheduler
from apscheduler.triggers.cron import CronTrigger

from backend.library import tracing

from backend.library.time import KST, today
from backend.stock import application

log = logging.getLogger(__name__)


_JOB = "stock-catalog-refresh"


@tracing.traced_job(_JOB)
def refresh_catalog() -> None:
    application.refresh(today())


def register(scheduler: BaseScheduler) -> None:
    """매일 개장 전 1회. 기동 시 적재는 앱 lifespan이 따로 부른다.

    **07:40인 이유** — 개장은 NXT 프리마켓 08:00이라, 그 뒤에 갱신하면 신규 상장 종목이 프리마켓 동안
    필터에서 빠진다. KIS 마스터 파일은 **전날 저녁(18:55 KST 무렵)에 만들어져** 더 당겨도 손해가 없다."""
    scheduler.add_job(
        refresh_catalog,
        CronTrigger(hour=7, minute=40, timezone=KST),
        id=_JOB,
        replace_existing=True,
    )
