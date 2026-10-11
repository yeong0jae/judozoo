"""브로커 액세스 토큰 영속 저장소.

**토큰은 희소 자원이다.** 키움은 앱키당 하루 1개(재발급이 쌓이면 `start.html` 302로 거부),
KIS는 앱키당 1분 1회, 토스는 client당 1개만 유효하다.

메모리에만 두면 **재기동마다 발급을 소비**한다 — 2026-09-12 토요일 재기동 때 키움이 신규 발급을
거부해 국내 화면 전체가 죽었다. 그래서 DB에 남기고, DB가 안 되면 경고만 남기고 메모리로 동작한다."""

import logging
from datetime import UTC, datetime

from sqlalchemy import BigInteger, DateTime, String, Text, select

from sqlalchemy.orm import Mapped, mapped_column

from backend.library.db import Base, get_session_factory

log = logging.getLogger(__name__)


class BrokerToken(Base):
    """브로커별 최신 토큰 한 줄. `provider`로 찾는다(UNIQUE).

    `expires_at`은 **naive UTC**로 둔다 — 다른 테이블은 KST 벽시계를 쓰지만
    이 값은 사람이 읽을 일이 없고 인증 모듈이 전부 UTC로 계산하므로 변환을 한 번 줄인다.
    """

    __tablename__ = "broker_token"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    provider: Mapped[str] = mapped_column(String(16), unique=True, nullable=False)
    # 토스 토큰은 JWT라 750자를 넘는다 — varchar(512)로 뒀다가 저장이 통째로 실패했다.
    access_token: Mapped[str] = mapped_column(Text, nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)


def load(provider: str) -> tuple[str, datetime] | None:
    """살아 있는 토큰이 있으면 (토큰, 만료시각aware)로. 없거나 만료됐으면 None."""
    try:
        with get_session_factory()() as session:
            row = session.scalar(select(BrokerToken).where(BrokerToken.provider == provider))
            if row is None:
                return None
            expires_at = row.expires_at.replace(tzinfo=UTC)
            if expires_at <= datetime.now(UTC):
                return None
            log.info("%s 토큰을 DB에서 복원 — 만료 %s", provider, expires_at)
            return row.access_token, expires_at
    except Exception:
        log.warning("%s 토큰 복원 실패 — 새로 발급한다", provider, exc_info=True)
        return None


def save(provider: str, token: str, expires_at: datetime) -> None:
    """발급받은 토큰을 남긴다. 같은 provider는 덮어쓴다."""
    naive = expires_at.astimezone(UTC).replace(tzinfo=None)
    now = datetime.now(UTC).replace(tzinfo=None)
    try:
        with get_session_factory()() as session:
            row = session.scalar(select(BrokerToken).where(BrokerToken.provider == provider))
            if row is None:
                session.add(
                    BrokerToken(
                        provider=provider, access_token=token, expires_at=naive,
                        created_at=now, updated_at=now,
                    )
                )
            else:
                row.access_token, row.expires_at, row.updated_at = token, naive, now
            session.commit()
    except Exception:
        log.warning("%s 토큰 저장 실패 — 메모리 캐시로만 동작한다", provider, exc_info=True)


def clear(provider: str) -> None:
    """토큰이 외부에서 무효화됐을 때. 남아 있으면 재기동 시 죽은 토큰을 되살린다."""
    try:
        with get_session_factory()() as session:
            row = session.scalar(select(BrokerToken).where(BrokerToken.provider == provider))
            if row is not None:
                session.delete(row)
                session.commit()
    except Exception:
        log.warning("%s 토큰 삭제 실패", provider, exc_info=True)
