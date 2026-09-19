"""의견 접수."""

import logging
from datetime import datetime

from sqlalchemy.orm import Session

from backend.auth.domain import CurrentUser
from backend.feedback.domain import Feedback

log = logging.getLogger(__name__)


def receive(db: Session, user: CurrentUser, content: str, now: datetime) -> None:
    """저장이 곧 접수다. 알림은 Grafana가 이 테이블을 보고 보낸다.

    백엔드에서 직접 Slack을 부르지 않는 이유 — 가입 알림과 같다. 웹훅을 앱이 들고 있으면
    시크릿이 하나 늘고, 전송 실패가 사용자 요청의 실패가 된다. 행이 생긴 것이 사실이고
    알리는 일은 그 사실을 보는 쪽의 몫이다.
    """
    db.add(Feedback.write(user.id, content, now))
    db.commit()
    # 알림이 안 오면 규칙이 문제인지 접수가 문제인지 여기서 갈린다.
    log.info("의견 접수 — %d자", len(content.strip()))
