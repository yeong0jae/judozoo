"""구글 OAuth 흐름과 가입자 기록."""

import logging
from dataclasses import replace
from datetime import datetime

from authlib.integrations.starlette_client import OAuth
from sqlalchemy import select
from sqlalchemy.orm import Session

from backend.auth.domain import AppUser, CurrentUser
from backend.feedback import application as feedback
from backend.settings import get_settings

log = logging.getLogger(__name__)

_GOOGLE_METADATA = "https://accounts.google.com/.well-known/openid-configuration"

_oauth: OAuth | None = None


def google_client():
    """authlib 클라이언트. 프로세스당 한 번 지연 생성해 재사용한다."""
    global _oauth
    if _oauth is None:
        cfg = get_settings().google
        oauth = OAuth()
        oauth.register(
            name="google",
            client_id=cfg.client_id,
            client_secret=cfg.client_secret,
            server_metadata_url=_GOOGLE_METADATA,
            client_kwargs={"scope": "openid email"},
        )
        _oauth = oauth
    return _oauth.google


def is_configured() -> bool:
    cfg = get_settings().google
    return bool(cfg.client_id and cfg.client_secret)


def user_from_token(token: dict) -> CurrentUser | None:
    """토큰 응답에서 사용자를 꺼낸다.

    서명·`aud`·`iss`·만료·nonce는 `authorize_access_token()`이 이미 검증했다.
    """
    claims = token.get("userinfo") or {}
    sub, email = claims.get("sub"), claims.get("email")
    if not sub or not email:
        log.warning("ID 토큰에 sub/email이 없다 — scope 설정을 확인해야 한다")
        return None
    return CurrentUser(google_sub=str(sub), email=str(email))


def with_internal_id(db: Session, user: CurrentUser) -> CurrentUser:
    """V006 이전 세션처럼 `id`가 없는 사용자에게 채워 준다. 없는 사람이면 그대로 돌려준다.

    채운 값은 세션에 들어가므로 옛 세션 하나당 **한 번만** 읽는다.
    """
    if user.id is not None:
        return user
    row = db.scalar(select(AppUser).where(AppUser.google_sub == user.google_sub))
    return user if row is None else replace(user, id=row.id)


def record_login(db: Session, user: CurrentUser, now: datetime) -> CurrentUser:
    """최초면 생성, 재방문이면 마지막 로그인 시각만 갱신. **내부 식별자를 채워 돌려준다** —
    세션에 넣어 두면 이후 요청이 DB 없이 로그에 사용자를 싣는다.
    """
    row = db.scalar(select(AppUser).where(AppUser.google_sub == user.google_sub))
    if row is None:
        row = AppUser(
            google_sub=user.google_sub,
            email=user.email,
            created_at=now,
            last_login_at=now,
        )
        db.add(row)
    else:
        row.email = user.email  # 계정 이메일이 바뀌었을 수 있다
        row.last_login_at = now
    db.commit()
    # 콜백 중엔 세션이 아직 없어 관문이 사용자를 못 심으므로 여기서 직접 싣는다.
    log.info("로그인", extra={"user": row.id})
    return replace(user, id=row.id)


def withdraw(db: Session, user: CurrentUser) -> None:
    """탈퇴 — 가입자 행과 그 사람이 보낸 의견을 한 트랜잭션으로 지운다.

    옛 세션엔 `id`가 없을 수 있어 `google_sub`로 찾는다. 이미 없으면(두 번 누름) 아무것도 안 한다.
    """
    row = db.scalar(select(AppUser).where(AppUser.google_sub == user.google_sub))
    if row is None:
        return
    erased = feedback.erase_by(db, row.id)
    db.delete(row)
    db.commit()
    log.info("탈퇴 — 의견 %d건 함께 삭제", erased, extra={"user": row.id})
