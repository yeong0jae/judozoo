"""주도주 캘린더 스냅샷 — 국내 20:01 KST, 해외 16:01 뉴욕.

둘 다 **홈 풀이 멈춘 뒤**다. 풀은 세션이 끝나면 다음 세션까지 마지막 값을 들고 있으므로
(`_pool_ttl`) 이 시각에 읽은 값이 그날 밤 홈에 뜬 값과 같다.

해외를 KST로 걸지 않는다 — 05:01 고정이면 서머타임이 끝난 뒤(11월) 정규장 중에 찍힌다.
"""

import logging

from apscheduler.schedulers.base import BaseScheduler
from apscheduler.triggers.cron import CronTrigger

from backend.leadercalendar import application
from backend.library import metrics, tracing
from backend.library.db import get_session_factory
from backend.library.time import KST, now
from backend.market import calendar
from backend.market.calendar import Region

log = logging.getLogger(__name__)

_DOMESTIC_JOB = "leader-calendar-domestic"
_OVERSEAS_JOB = "leader-calendar-overseas"


@tracing.traced_job(_DOMESTIC_JOB)
def snapshot_domestic() -> None:
    _snapshot(Region.KR, _DOMESTIC_JOB, application.snapshot_domestic)


@tracing.traced_job(_OVERSEAS_JOB)
def snapshot_overseas() -> None:
    _snapshot(Region.US, _OVERSEAS_JOB, application.snapshot_overseas)


def _snapshot(region: Region, job: str, take) -> None:
    """거래일은 **현지 날짜**다 — 16:01 뉴욕은 KST로 다음 날 새벽이라 `today()`를 쓰면 하루 밀린다.

    휴장일은 건너뛰지 않고 **휴장으로 남긴다** — 캘린더가 빈 칸과 휴장을 가를 수 있게.
    """
    trade_date = region.today()
    try:
        with get_session_factory()() as session:
            if calendar.is_holiday(region):
                application.record_closed(session, region, trade_date, now())
            else:
                take(session, trade_date, now())
    except Exception:
        # 삼킨 예외는 APScheduler에 성공으로 보인다 — 카운터를 여기서 직접 올린다.
        metrics.job_failed(job)
        log.warning("주도주 캘린더 스냅샷 실패 %s %s", region.value, trade_date, exc_info=True)


def register(scheduler: BaseScheduler) -> None:
    scheduler.add_job(
        snapshot_domestic,
        CronTrigger(day_of_week="mon-fri", hour=20, minute=1, timezone=KST),
        id=_DOMESTIC_JOB, replace_existing=True,
    )
    scheduler.add_job(
        snapshot_overseas,
        CronTrigger(day_of_week="mon-fri", hour=16, minute=1, timezone=Region.US.zone),
        id=_OVERSEAS_JOB, replace_existing=True,
    )
