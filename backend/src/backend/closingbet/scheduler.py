"""모의 종가베팅 — 매분 확인. 20:00 체결과 아침 정산 모두 `application.tick`이 한다.

20시대 내내 돈다 — 일봉 종가가 늦게 오면(027 0단계) 다음 분에 다시 체결을 시도한다.
"""

import logging

from apscheduler.schedulers.base import BaseScheduler
from apscheduler.triggers.cron import CronTrigger

from backend.closingbet import application
from backend.library import metrics, tracing
from backend.library.db import get_session_factory
from backend.library.time import KST, now

log = logging.getLogger(__name__)

_JOB = "closingbet-tick"


@tracing.traced_job(_JOB)
def run() -> None:
    try:
        with get_session_factory()() as session:
            application.tick(session, now())
    except Exception:
        # 삼킨 예외는 APScheduler에 성공으로 보인다 — 카운터를 여기서 직접 올린다.
        metrics.job_failed(_JOB)
        log.warning("종가베팅 체결·정산 실패", exc_info=True)


def register(scheduler: BaseScheduler) -> None:
    scheduler.add_job(
        run,
        CronTrigger(day_of_week="mon-fri", hour="8-20", minute="*", second=5, timezone=KST),
        id=_JOB, replace_existing=True, max_instances=1, coalesce=True,
    )
