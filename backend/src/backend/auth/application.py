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
    """authlib 클라이언트. 메타데이터(토큰 엔드포인트·공개키 URL)는 authlib이 받아서 캐싱한다.

    `get_settings()`가 프로세스당 한 번이라 여기서도 지연 생성해 두고 재사용한다.
    """
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

    `authorize_access_token()`이 ID 토큰의 서명·`aud`·`iss`·만료·nonce를 이미 검증하고
    `userinfo`에 클레임을 채워 준다. 여기서 다시 디코딩하지 않는다 —
    검증 없이 디코딩하면 그건 인증이 아니다.
    """
    claims = token.get("userinfo") or {}
    sub, email = claims.get("sub"), claims.get("email")
    if not sub or not email:
        log.warning("ID 토큰에 sub/email이 없다 — scope 설정을 확인해야 한다")
        return None
    return CurrentUser(google_sub=str(sub), email=str(email))


def with_internal_id(db: Session, user: CurrentUser) -> CurrentUser:
    """내부 식별자가 없는 사용자에게 채워 준다. 없는 사람이면 그대로 돌려준다.

    대체 키(V006) 이전에 발급된 세션에는 `id`가 없다. 그 사용자는 다시 로그인하기 전까지
    로그에 실리지 않아 여정에서 빠진다. 다시 로그인하라고 요구하는 대신 여기서 한 번 채운다.

    옛 세션 하나당 **한 번만** 읽는다 — 채워진 값이 세션에 들어가면 다시 오지 않는다.
    """
    if user.id is not None:
        return user
    row = db.scalar(select(AppUser).where(AppUser.google_sub == user.google_sub))
    return user if row is None else replace(user, id=row.id)


def record_login(db: Session, user: CurrentUser, now: datetime) -> CurrentUser:
    """최초면 생성, 재방문이면 마지막 로그인 시각만 갱신. **내부 식별자를 채워 돌려준다.**

    돌려주는 이유 — 이 값이 세션에 들어가야 이후 요청이 DB를 다시 읽지 않고도
    로그에 사용자를 실을 수 있다.
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
    # 이 줄이 여정의 시작점이다. 콜백을 처리하는 동안에는 세션이 아직 없어서
    # 관문이 사용자를 심지 못하므로, 여기서만 직접 싣는다.
    log.info("로그인", extra={"user": row.id})
    return replace(user, id=row.id)


def withdraw(db: Session, user: CurrentUser) -> None:
    """탈퇴 — 가입자 행과 그 사람이 보낸 의견을 한 트랜잭션으로 지운다.

    `google_sub`로 찾는다 — 옛 세션에는 내부 `id`가 없을 수 있다. 이미 없는 사람이면(두 번 누름)
    할 일이 없을 뿐 실패는 아니다. 다시 로그인하면 새 가입자로 처음부터 기록된다.
    """
    row = db.scalar(select(AppUser).where(AppUser.google_sub == user.google_sub))
    if row is None:
        return
    erased = feedback.erase_by(db, row.id)
    db.delete(row)
    db.commit()
    log.info("탈퇴 — 의견 %d건 함께 삭제", erased, extra={"user": row.id})
