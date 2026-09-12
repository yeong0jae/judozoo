"""APScheduler 설정.

Kotlin `@Scheduled`에 대응한다. 시각 기준은 KST로 고정 — 컨테이너 기본 UTC를 그대로 쓰면
"06:10 화~토" 같은 cron이 9시간 어긋난다.

테스트에서는 켜지 않는다(Kotlin `@Profile("!test")`에 대응).
"""

import logging
import os
from zoneinfo import ZoneInfo

from apscheduler.schedulers.background import BackgroundScheduler

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

    from backend.overseasleadingstock.scheduler import register as register_overseas
    from backend.leadingstock.scheduler import register as register_leading
    from backend.market.scheduler import register as register_market
    from backend.stock.scheduler import register as register_stock
    from backend.theme.scheduler import register as register_theme

    _scheduler = BackgroundScheduler(timezone=KST)
    register_overseas(_scheduler)
    register_stock(_scheduler)
    register_market(_scheduler)
    register_theme(_scheduler)
    register_leading(_scheduler)
    _scheduler.start()
    log.info("스케줄러 시작 — 등록된 작업 %d개", len(_scheduler.get_jobs()))
    return _scheduler


def shutdown() -> None:
    global _scheduler
    if _scheduler is not None:
        _scheduler.shutdown(wait=False)
        _scheduler = None
