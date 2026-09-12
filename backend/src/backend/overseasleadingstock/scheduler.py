"""해외 주도주 스케줄 작업."""

import logging
from datetime import datetime

from apscheduler.schedulers.base import BaseScheduler
from apscheduler.triggers.cron import CronTrigger

from backend.library.db import get_session_factory
from backend.library.scheduler import KST
from backend.overseasleadingstock import application

log = logging.getLogger(__name__)


def capture_index_close() -> None:
    """미국 정규장 마감 뒤 나스닥종합 지수를 스냅샷으로 적재.

    한국시간 06:10 단일 cron — 서머타임(마감 05:00)·표준시(06:00) 모두 마감 뒤라
    DST 분기 없이 종가를 받는다. 영업일은 응답이 알려주는 실제 영업일을 쓰므로
    미국 휴장일엔 중복 없이 멱등.
    """
    try:
        with get_session_factory()() as session:
            saved = application.capture_index_close(session, datetime.now(KST).replace(tzinfo=None))
        if saved > 0:
            log.info("해외지수 마감 스냅샷 %d건 적재", saved)
    except Exception:
        log.warning("해외지수 마감 스냅샷 적재 실패", exc_info=True)


def register(scheduler: BaseScheduler) -> None:
    scheduler.add_job(
        capture_index_close,
        CronTrigger(day_of_week="tue-sat", hour=6, minute=10, timezone=KST),
        id="overseas-index-close-capture",
        replace_existing=True,
    )
