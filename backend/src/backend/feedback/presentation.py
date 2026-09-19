"""의견 보내기 — 서비스 안에서 한마디 남기는 유일한 경로."""

from datetime import datetime
from typing import Annotated

from fastapi import APIRouter, Depends
from pydantic import BaseModel, StringConstraints
from sqlalchemy.orm import Session

from backend.auth.domain import CurrentUser
from backend.auth.presentation import require_login
from backend.feedback import application
from backend.library.db import get_db
from backend.library.web import ApiResponse

router = APIRouter()


class FeedbackRequest(BaseModel):
    """공백만 보낸 것은 빈 것과 같다 — 먼저 털고 나서 길이를 본다.

    2000자는 Slack 알림에 실을 수 있는 한도가 아니라(알림은 앞부분만 싣는다) 한 사람이
    한 번에 남길 만한 분량의 상한이다. 화면도 같은 값으로 막는다.
    """

    content: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=2000)]


@router.post("/api/feedback")
def send_feedback(
    body: FeedbackRequest,
    user: Annotated[CurrentUser, Depends(require_login)],
    db: Annotated[Session, Depends(get_db)],
) -> ApiResponse[None]:
    """로그인이 전제다. 허용목록(auth/gate.py)에 없으므로 관문이 이미 막지만,
    보낸 사람을 손에 쥐어야 해서 의존성으로 한 번 더 받는다."""
    application.receive(db, user, body.content, datetime.now())
    return ApiResponse.created(None)
