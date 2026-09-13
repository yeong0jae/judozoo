"""로그인 라우트와 인증 의존성."""

import logging
from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import RedirectResponse
from pydantic import BaseModel
from sqlalchemy.orm import Session

from backend.auth import application
from backend.auth.domain import SESSION_KEY, CurrentUser
from backend.library.db import get_db
from backend.library.web import ApiResponse

log = logging.getLogger(__name__)

router = APIRouter(prefix="/api/auth")


def current_user(request: Request) -> CurrentUser | None:
    """세션 쿠키에서 사용자를 복원한다. 서명 검증은 SessionMiddleware가 이미 했다."""
    return CurrentUser.from_session(request.session.get(SESSION_KEY))


def require_login(request: Request) -> CurrentUser:
    """보호할 라우트에 `Depends(require_login)`으로 붙인다."""
    user = current_user(request)
    if user is None:
        # 프론트가 기대하는 봉투 모양 그대로 — main.py의 핸들러가 감싼다.
        raise HTTPException(status_code=401, detail="UNAUTHORIZED")
    return user


@router.get("/login")
async def login(request: Request):
    if not application.is_configured():
        raise HTTPException(status_code=503, detail="OAUTH_NOT_CONFIGURED")
    # authlib이 state·nonce를 만들어 세션에 넣고 콜백에서 대조한다 (CSRF 방어).
    redirect_uri = request.app.state.google_redirect_uri
    return await application.google_client().authorize_redirect(request, redirect_uri)


@router.get("/callback")
async def callback(request: Request, db: Session = Depends(get_db)):
    try:
        token = await application.google_client().authorize_access_token(request)
    except Exception:
        # state 불일치·code 만료·네트워크 실패가 전부 여기로 온다. 사용자에겐 첫 화면을 보여준다.
        log.warning("구글 콜백 처리 실패", exc_info=True)
        return RedirectResponse("/?login=failed", status_code=302)

    user = application.user_from_token(token)
    if user is None:
        return RedirectResponse("/?login=failed", status_code=302)

    application.record_login(db, user, datetime.now())
    request.session[SESSION_KEY] = user.to_session()
    return RedirectResponse("/", status_code=302)


@router.post("/logout")
def logout(request: Request) -> ApiResponse[None]:
    request.session.pop(SESSION_KEY, None)
    return ApiResponse.ok(None)


class MeResponse(BaseModel):
    authenticated: bool
    email: str | None = None


@router.get("/me")
def me(request: Request) -> ApiResponse[MeResponse]:
    """프론트가 헤더 렌더와 로그인 유도 판단에 쓴다. 미로그인도 200으로 답한다."""
    user = current_user(request)
    if user is None:
        return ApiResponse.ok(MeResponse(authenticated=False))
    return ApiResponse.ok(MeResponse(authenticated=True, email=user.email))
