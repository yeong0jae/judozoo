"""APScheduler 설정.

시각 기준은 KST로 고정 — 컨테이너 기본 UTC를 그대로 쓰면 "06:10 화~토" 같은 cron이 9시간 어긋난다.
테스트에서는 켜지 않는다."""

import logging
import os
from zoneinfo import ZoneInfo

from apscheduler.events import (
    EVENT_JOB_ERROR,
    EVENT_JOB_EXECUTED,
    EVENT_JOB_MISSED,
    EVENT_JOB_SUBMITTED,
)
from apscheduler.schedulers.background import BackgroundScheduler

from backend.library import metrics

log = logging.getLogger(__name__)

KST = ZoneInfo("Asia/Seoul")

_scheduler: BackgroundScheduler | None = None


def schedulers_enabled() -> bool:
    """`SCHEDULERS_ENABLED=false`면 끈다. 테스트·로컬 실험에서 외부 API를 두드리지 않기 위한 것."""
    return os.getenv("SCHEDULERS_ENABLED", "true").lower() not in ("false", "0", "no")


def start() -> BackgroundScheduler | None:
    global _scheduler
    if not schedulers_enabled():
        log.info("스케줄러 비활성화 (SCHEDULERS_ENABLED)")
        return None
    if _scheduler is not None:
        return _scheduler

    _scheduler = BackgroundScheduler(timezone=KST)
    # 모든 작업이 Kotlin으로 옮겨 갔다(028) — 주도주·해외 주도주·시황·종목 카탈로그·타임라인·캘린더·왜 오르나·종가베팅.
    # DB에 쓰는 작업은 두 쪽이 돌면 서로를 덮고, 메모리를 채우는 작업은 읽는 Python 기능이 더는 없다.
    # 스케줄러 자체는 남겨 둔다 — `backend/`를 지울 때(4단계) 함께 걷는다.
    # 잡마다 데코레이터를 다는 대신 리스너 하나. 잡을 새로 추가할 때 계측을 빠뜨릴 데가 없다.
    _scheduler.add_listener(metrics.on_job_submitted, EVENT_JOB_SUBMITTED)
    _scheduler.add_listener(
        metrics.on_job_event, EVENT_JOB_EXECUTED | EVENT_JOB_ERROR | EVENT_JOB_MISSED
    )
    _scheduler.start()
    log.info("스케줄러 시작 — 등록된 작업 %d개", len(_scheduler.get_jobs()))
    return _scheduler


def shutdown() -> None:
    global _scheduler
    if _scheduler is not None:
        _scheduler.shutdown(wait=False)
        _scheduler = None
