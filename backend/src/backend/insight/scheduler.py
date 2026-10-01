"""왜 오르나 — 매분 확인. 만들지 말지·무엇을 만들지는 도메인(`pick`)이 정한다.

국내는 KST 08~20시, 해외는 뉴욕 08~18시에만 깨어난다(생성 시간 밖의 분은 `run`이 바로 끝낸다).
해외를 KST로 걸지 않는다 — 서머타임이 끝나면(11월) 한 시간 어긋난다.

한 번에 5종목을 만들면 1분을 넘긴다. 겹친 실행은 APScheduler가 건너뛰는데, 정해진 시각을 5분 폭으로
보고(`Schedule.is_slot`) 30분 규칙이 중복을 막아 정각을 놓치지 않는다.
"""

import logging

from apscheduler.schedulers.base import BaseScheduler
from apscheduler.triggers.cron import CronTrigger

from backend.insight import application
from backend.library import metrics, tracing
from backend.library.db import get_session_factory
from backend.library.time import KST, now
from backend.market import calendar
from backend.market.calendar import Region

log = logging.getLogger(__name__)

_DOMESTIC_JOB = "insight-reason-domestic"
_OVERSEAS_JOB = "insight-reason-overseas"


@tracing.traced_job(_DOMESTIC_JOB)
def run_domestic() -> None:
    _run(Region.KR, _DOMESTIC_JOB)


@tracing.traced_job(_OVERSEAS_JOB)
def run_overseas() -> None:
    _run(Region.US, _OVERSEAS_JOB)


def _run(region: Region, job: str) -> None:
    if calendar.is_holiday(region):
        return
    try:
        with get_session_factory()() as session:
            made = application.run(session, region, now())
        if made:
            log.info("왜 오르나 %s — %d종목", region.value, made)
    except Exception:
        # 삼킨 예외는 APScheduler에 성공으로 보인다 — 카운터를 여기서 직접 올린다.
        metrics.job_failed(job)
        log.warning("왜 오르나 실행 실패 %s", region.value, exc_info=True)


def register(scheduler: BaseScheduler) -> None:
    scheduler.add_job(
        run_domestic,
        CronTrigger(day_of_week="mon-fri", hour="8-20", minute="*", second=0, timezone=KST),
        id=_DOMESTIC_JOB, replace_existing=True, max_instances=1, coalesce=True,
    )
    scheduler.add_job(
        run_overseas,
        CronTrigger(day_of_week="mon-fri", hour="8-18", minute="*", second=0, timezone=Region.US.zone),
        id=_OVERSEAS_JOB, replace_existing=True, max_instances=1, coalesce=True,
    )
