"""주도주 타임라인 스냅샷 — 매분 0초. 국내 08:00~20:00 KST, 해외 04:00~20:00 뉴욕(마감 분 포함).

풀은 장중 15초마다 갈아 끼워지는 캐시라, 여기서 읽는다고 브로커 호출이 늘지 않는다.
해외를 KST로 걸지 않는다 — 서머타임이 끝나면(11월) 한 시간 어긋난다.
"""

import logging
from datetime import datetime

from apscheduler.schedulers.base import BaseScheduler
from apscheduler.triggers.cron import CronTrigger

from backend.leadertimeline import application
from backend.leadertimeline.domain import captures
from backend.library import metrics, tracing
from backend.library.db import get_session_factory
from backend.library.time import KST, now
from backend.market import calendar
from backend.market.calendar import Region

log = logging.getLogger(__name__)

_DOMESTIC_JOB = "leader-timeline-domestic"
_OVERSEAS_JOB = "leader-timeline-overseas"


@tracing.traced_job(_DOMESTIC_JOB)
def snapshot_domestic() -> None:
    _snapshot(Region.KR, _DOMESTIC_JOB)


@tracing.traced_job(_OVERSEAS_JOB)
def snapshot_overseas() -> None:
    _snapshot(Region.US, _OVERSEAS_JOB)


def local_minute(region: Region, at_kst: datetime) -> datetime:
    """KST 벽시계를 그 시장의 현지 분(초 0)으로 — 해외는 뉴욕 시각. DB 열에 맞춰 naive로."""
    local = at_kst.replace(tzinfo=KST).astimezone(region.zone)
    return local.replace(tzinfo=None, second=0, microsecond=0)


def _snapshot(region: Region, job: str) -> None:
    """쉬는 구간·휴장일은 찍지 않는다. 실패하면 그 분을 남기지 않는다 — 화면엔 띠가 끊긴 분으로 보인다."""
    taken_at = now()
    at = local_minute(region, taken_at)
    if not captures(region, at.time()) or calendar.is_holiday(region):
        return
    try:
        with get_session_factory()() as session:
            application.snapshot(session, region, at, taken_at)
    except Exception:
        # 삼킨 예외는 APScheduler에 성공으로 보인다 — 카운터를 여기서 직접 올린다.
        metrics.job_failed(job)
        log.warning("주도주 타임라인 스냅샷 실패 %s %s", region.value, at, exc_info=True)


def register(scheduler: BaseScheduler) -> None:
    scheduler.add_job(
        snapshot_domestic,
        # 20시대는 20:00만 찍힌다 — 나머지 분은 `captures`가 거른다
        CronTrigger(day_of_week="mon-fri", hour="8-20", minute="*", second=0, timezone=KST),
        id=_DOMESTIC_JOB, replace_existing=True,
    )
    scheduler.add_job(
        snapshot_overseas,
        # 해외도 20시대는 20:00만 찍힌다
        CronTrigger(day_of_week="mon-fri", hour="4-20", minute="*", second=0, timezone=Region.US.zone),
        id=_OVERSEAS_JOB, replace_existing=True,
    )
