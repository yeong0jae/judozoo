"""구글 OAuth 흐름과 가입자 기록."""

import logging
from datetime import datetime

from authlib.integrations.starlette_client import OAuth
from sqlalchemy import select
from sqlalchemy.orm import Session

from backend.auth.domain import AppUser, CurrentUser, GoogleAccount
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


def account_from_token(token: dict) -> GoogleAccount | None:
    """토큰 응답에서 구글 계정을 꺼낸다.

    서명·`aud`·`iss`·만료·nonce는 `authorize_access_token()`이 이미 검증했다.
    """
    claims = token.get("userinfo") or {}
    sub, email = claims.get("sub"), claims.get("email")
    if not sub or not email:
        log.warning("ID 토큰에 sub/email이 없다 — scope 설정을 확인해야 한다")
        return None
    return GoogleAccount(sub=str(sub), email=str(email))


def record_login(db: Session, account: GoogleAccount, now: datetime) -> CurrentUser:
    """최초면 생성, 재방문이면 마지막 로그인 시각만 갱신. **내부 식별자로 사용자를 돌려준다** —
    세션에 넣어 두면 이후 요청이 DB 없이 로그에 사용자를 싣는다.
    """
    row = db.scalar(select(AppUser).where(AppUser.google_sub == account.sub))
    if row is None:
        row = AppUser(
            google_sub=account.sub,
            email=account.email,
            created_at=now,
            last_login_at=now,
        )
        db.add(row)
    else:
        row.email = account.email  # 계정 이메일이 바뀌었을 수 있다
        row.last_login_at = now
    db.commit()
    # 콜백 중엔 세션이 아직 없어 관문이 사용자를 못 심으므로 여기서 직접 싣는다.
    log.info("로그인", extra={"user": row.id})
    return CurrentUser(id=row.id, email=row.email)


def withdraw(db: Session, user: CurrentUser) -> None:
    """탈퇴 — 가입자 행과 그 사람이 보낸 의견을 한 트랜잭션으로 지운다.

    이미 없으면(두 번 누름) 아무것도 안 한다.
    """
    row = db.get(AppUser, user.id)
    if row is None:
        return
    erased = feedback.erase_by(db, row.id)
    db.delete(row)
    db.commit()
    log.info("탈퇴 — 의견 %d건 함께 삭제", erased, extra={"user": row.id})
