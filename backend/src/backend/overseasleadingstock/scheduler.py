"""해외 주도주 폴러 — 후보 풀 갱신(10s) / 분봉 마감 확정(뉴욕 20:01).

장중에는 세 거래소 거래대금 순위를 늘 채워 둔다. 화면과 헤더 티커가 KIS 응답(거래소 3번)을
기다리지 않게 하려는 것이다. 장 밖에는 쉬고, 부르는 쪽이 채운 값을 다음 장까지 쓴다.
"""

import logging

from apscheduler.schedulers.base import BaseScheduler
from apscheduler.triggers.cron import CronTrigger
from apscheduler.triggers.interval import IntervalTrigger

from backend.library import metrics, tracing
from backend.market import calendar
from backend.overseasleadingstock import application

log = logging.getLogger(__name__)

_POOL_REFRESH_JOB = "overseas-ranking-pool-refresher"
_MINUTE_SETTLE_JOB = "overseas-minute-settler"

# 후보 풀 캐시(TTL 20초)를 만료 전에 갈아 끼우는 주기
_POOL_REFRESH_SECONDS = 15


@tracing.traced_job(_POOL_REFRESH_JOB)
def refresh_ranking_pool() -> None:
    """실패해도 기존 값은 TTL이 남은 동안 그대로 쓰인다. 다음 회차가 다시 시도한다."""
    holiday, trading_hours = calendar.us_market_status()
    if holiday or not trading_hours:
        return
    try:
        application.refresh_ranking_pool()
    except Exception:
        metrics.job_failed(_POOL_REFRESH_JOB)
        log.warning("해외 후보 풀 갱신 실패", exc_info=True)


@tracing.traced_job(_MINUTE_SETTLE_JOB)
def settle_minutes() -> None:
    """애프터마켓(뉴욕 20:00)이 끝나고 1분 뒤 들고 있는 종목의 오늘 봉을 굳혀 DB에 넘긴다.

    20:01로 둔 건 마지막 봉이 굳을 틈이다 — 국내 마감 확정과 같다. 이게 없으면 다음에 열 때
    끝난 날을 KIS에서 다시 받는다.
    """
    if calendar.is_holiday(calendar.Region.US):
        return
    try:
        failures = application.settle_minutes()
    except Exception:
        metrics.job_failed(_MINUTE_SETTLE_JOB)
        log.warning("해외 분봉 마감 확정 실패", exc_info=True)
        return
    if failures:
        metrics.job_failed(_MINUTE_SETTLE_JOB)


def register(scheduler: BaseScheduler) -> None:
    scheduler.add_job(
        refresh_ranking_pool,
        IntervalTrigger(seconds=_POOL_REFRESH_SECONDS),
        id=_POOL_REFRESH_JOB, replace_existing=True,
    )
    scheduler.add_job(
        settle_minutes,
        # 뉴욕 시각으로 건다 — 서머타임에 따라 한국 시각으로는 09:01·10:01로 바뀐다
        CronTrigger(day_of_week="mon-fri", hour=20, minute=1, timezone=calendar.Region.US.zone),
        id=_MINUTE_SETTLE_JOB, replace_existing=True,
    )
