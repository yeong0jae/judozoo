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
    """매일 개장 전 1회. 기동 시 적재는 앱 lifespan이 따로 부른다.

    **07:40인 이유** — 개장은 NXT 프리마켓 08:00이다(`leadingstock.scheduler._SNAPSHOT_START`).
    원래 08:30이었는데 그건 정규장 09:00 기준이라, NXT가 생긴 뒤로는 개장 30분 뒤에야
    카탈로그가 갱신됐다. 신규 상장 종목이 프리마켓 첫 30분 동안 필터에서 조용히 빠졌다.

    더 당겨도 손해가 없다 — KIS 마스터 파일은 **전날 저녁에 만들어진다.** 2026-09-17 측정에서
    국내 2개·해외 3개 파일의 last-modified가 전부 같은 날 18:55 KST였다(11초 차). 아침 배치는
    없고, 07:40에 받는 파일은 08:30에 받을 파일과 동일하다.
    """
    scheduler.add_job(
        refresh_catalog,
        CronTrigger(hour=7, minute=40, timezone=KST),
        id="stock-catalog-refresh",
        replace_existing=True,
    )
