"""로그인한 사용자와 그 영속 기록."""

from dataclasses import dataclass
from datetime import datetime

from sqlalchemy import BigInteger, DateTime, String
from sqlalchemy.orm import Mapped, mapped_column

from backend.library.db import Base

SESSION_KEY = "user"


@dataclass(frozen=True)
class CurrentUser:
    """세션 쿠키에 담기는 최소 정보. 쿠키 크기를 키우지 않으려고 꼭 필요한 것만 둔다.

    `id`는 로그인 직후 DB에서 채워 넣는다. 요청마다 로그에 실으려면 여기 있어야 하고,
    없으면 요청마다 `app_user`를 한 번씩 읽어야 한다.

    구글에서 막 받아온 상태에서는 아직 `id`가 없으므로 **없는 것을 허용한다.**
    로그인 전에 발급된 옛 세션도 같은 모양이라, 그 사용자는 다시 로그인할 때까지
    로그에 사용자가 안 실린다(관문은 그대로 통과한다).
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
    """가입자 기록. 홍보를 시작하면 "몇 명이 쓰는가"를 알 수 있는 유일한 지표다.

    키는 **이 테이블이 발급하는 `id`** 다. 외부 식별자를 키로 쓰지 않는 이유 —
    구글의 `sub`은 클라이언트별 값이 아니라 모든 앱에 같은 값을 주는 계정 식별자라,
    영구적이고 교체할 수 없으며 다른 서비스의 데이터와 결합된다. 그런 값을 로그처럼
    여러 곳으로 퍼지는 자리에 담으면 되돌릴 방법이 없다. `id`는 이 시스템 밖에서
    아무 의미가 없다.

    `google_sub`은 외부 계정을 잇는 열로 남는다. UNIQUE라 중복 가입은 그대로 막힌다.
    이메일은 바뀔 수 있어서 어느 쪽으로도 키가 아니다.
    """

    __tablename__ = "app_user"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    google_sub: Mapped[str] = mapped_column(String(64), unique=True, nullable=False)
    email: Mapped[str] = mapped_column(String(255), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    last_login_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
