"""로그인한 사용자와 그 영속 기록."""

from dataclasses import dataclass
from datetime import datetime

from sqlalchemy import BigInteger, DateTime, String
from sqlalchemy.orm import Mapped, mapped_column

from backend.library.db import Base

SESSION_KEY = "user"


@dataclass(frozen=True)
class GoogleAccount:
    """구글이 확인해 준 계정. 로그인 콜백 안에서만 쓰고 세션에는 담지 않는다."""

    sub: str
    email: str


@dataclass(frozen=True)
class CurrentUser:
    """세션 쿠키에 담기는 최소 정보.

    **구글 `sub`은 담지 않는다.** 쿠키는 서명만 되고 암호화되지 않아 누구나 읽을 수 있고,
    요청마다 실려 다닌다. 사람을 가리키는 값은 이 시스템이 발급한 `id`면 충분하다.
    """

    id: int
    email: str

    def to_session(self) -> dict[str, object]:
        return {"id": self.id, "email": self.email}

    @staticmethod
    def from_session(raw: object) -> "CurrentUser | None":
        if not isinstance(raw, dict):
            return None
        user_id, email = raw.get("id"), raw.get("email")
        # bool은 int의 하위 타입이라 따로 거른다
        if not isinstance(user_id, int) or isinstance(user_id, bool) or not isinstance(email, str):
            return None
        return CurrentUser(id=user_id, email=email)


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
