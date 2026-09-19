"""사용자가 보낸 의견."""

from datetime import datetime

from sqlalchemy import BigInteger, DateTime, Text
from sqlalchemy.orm import Mapped, mapped_column

from backend.library.db import Base


class Feedback(Base):
    """의견 한 건.

    **누가 썼는지는 `user_id`로만 남긴다.** 이메일은 `app_user`에 이미 있고, 여기에 또
    적으면 같은 개인정보가 테이블 두 곳에 흩어진다. 번호로 누구인지 보는 방법은
    가입 알림과 같다 — Grafana "이용 현황"의 가입자 표.

    `app_user.id`를 가리키지만 FK는 걸지 않는다. 코드베이스 전체에 FK가 없고(V006),
    가입 기록이 지워졌다고 해서 받은 의견까지 사라질 이유도 없다.

    대체 키(V006) 이전에 발급된 옛 세션에는 `id`가 없어 비워 둘 수 있다. 화면이 뜰 때마다
    `/api/auth/me`가 채워 주므로 실제로는 거의 오지 않지만, 그 한 건 때문에 의견을
    잃지는 않는다.
    """

    __tablename__ = "feedback"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)

    @staticmethod
    def write(user_id: int | None, content: str, now: datetime) -> "Feedback":
        """앞뒤 공백은 여기서 턴다 — 저장된 뒤에 털면 알림 문구와 저장값이 어긋난다."""
        return Feedback(user_id=user_id, content=content.strip(), created_at=now)
