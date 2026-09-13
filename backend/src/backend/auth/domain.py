"""로그인한 사용자와 그 영속 기록."""

from dataclasses import dataclass
from datetime import datetime

from sqlalchemy import DateTime, String
from sqlalchemy.orm import Mapped, mapped_column

from backend.library.db import Base

SESSION_KEY = "user"


@dataclass(frozen=True)
class CurrentUser:
    """세션 쿠키에 담기는 최소 정보. 쿠키 크기를 키우지 않으려고 두 필드만 둔다."""

    google_sub: str
    email: str

    def to_session(self) -> dict[str, str]:
        return {"sub": self.google_sub, "email": self.email}

    @staticmethod
    def from_session(raw: object) -> "CurrentUser | None":
        if not isinstance(raw, dict):
            return None
        sub, email = raw.get("sub"), raw.get("email")
        if not isinstance(sub, str) or not isinstance(email, str) or not sub:
            return None
        return CurrentUser(google_sub=sub, email=email)


class AppUser(Base):
    """가입자 기록. 홍보를 시작하면 "몇 명이 쓰는가"를 알 수 있는 유일한 지표다.

    키는 `google_sub` — 구글이 계정마다 주는 불변 식별자다. 이메일은 바뀔 수 있어서 키로 쓰지 않는다.
    """

    __tablename__ = "app_user"

    google_sub: Mapped[str] = mapped_column(String(64), primary_key=True)
    email: Mapped[str] = mapped_column(String(255), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    last_login_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
