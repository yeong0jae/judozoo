"""해외 주도주 폴러 — 후보 풀 갱신(10s).

장중에는 세 거래소 거래대금 순위를 늘 채워 둔다. 화면과 헤더 티커가 KIS 응답(거래소 3번)을
기다리지 않게 하려는 것이다. 장 밖에는 쉬고, 부르는 쪽이 채운 값을 다음 장까지 쓴다.
"""

import logging

from apscheduler.schedulers.base import BaseScheduler
from apscheduler.triggers.interval import IntervalTrigger

from backend.library import metrics, tracing
from backend.market import calendar
from backend.overseasleadingstock import application

log = logging.getLogger(__name__)

_POOL_REFRESH_JOB = "overseas-ranking-pool-refresher"

# 후보 풀 캐시(TTL 15초)를 만료 전에 갈아 끼우는 주기
_POOL_REFRESH_SECONDS = 10


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


def register(scheduler: BaseScheduler) -> None:
    scheduler.add_job(
        refresh_ranking_pool,
        IntervalTrigger(seconds=_POOL_REFRESH_SECONDS),
        id=_POOL_REFRESH_JOB, replace_existing=True,
    )
