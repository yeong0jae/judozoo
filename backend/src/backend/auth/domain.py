"""로그인한 사용자와 그 영속 기록."""

from dataclasses import dataclass
from datetime import datetime

from sqlalchemy import BigInteger, DateTime, String
from sqlalchemy.orm import Mapped, mapped_column

from backend.library.db import Base

SESSION_KEY = "user"


@dataclass(frozen=True)
class CurrentUser:
    """세션 쿠키에 담기는 최소 정보.

    `id`는 로그인 직후 DB에서 채운다 — 요청마다 로그에 실으려면 여기 있어야 한다.
    구글에서 막 받아온 상태와 옛 세션에는 아직 없어 **없는 것을 허용한다.**
    """

    google_sub: str
    email: str
    id: int | None = None

    def to_session(self) -> dict[str, object]:
        raw: dict[str, object] = {"sub": self.google_sub, "email": self.email}
        if self.id is not None:
            raw["id"] = self.id
        return raw

    @staticmethod
    def from_session(raw: object) -> "CurrentUser | None":
        if not isinstance(raw, dict):
            return None
        sub, email = raw.get("sub"), raw.get("email")
        if not isinstance(sub, str) or not isinstance(email, str) or not sub:
            return None
        user_id = raw.get("id")
        return CurrentUser(
            google_sub=sub,
            email=email,
            id=user_id if isinstance(user_id, int) else None,
        )


class AppUser(Base):
    """가입자 기록.

    키는 **이 테이블이 발급하는 `id`** 다. 구글 `sub`은 모든 앱에 같은 영구 계정 식별자라
    로그처럼 퍼지는 자리에 담으면 되돌릴 수 없다. `google_sub`은 UNIQUE로 중복 가입을 막고,
    이메일은 바뀔 수 있어 키가 아니다.
    """

    __tablename__ = "app_user"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    google_sub: Mapped[str] = mapped_column(String(64), unique=True, nullable=False)
    email: Mapped[str] = mapped_column(String(255), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    last_login_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
