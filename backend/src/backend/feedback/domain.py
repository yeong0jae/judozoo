"""사용자가 보낸 의견."""

from datetime import datetime

from sqlalchemy import BigInteger, DateTime, Index, Text
from sqlalchemy.orm import Mapped, mapped_column

from backend.library.db import Base


class Feedback(Base):
    """의견 한 건.

    **누가 썼는지는 `user_id`로만 남긴다** — 이메일까지 적으면 개인정보가 두 테이블에 흩어진다.
    FK는 걸지 않는다(V006). 옛 세션에서 받은 행은 `id`가 비어 있다.
    """

    __tablename__ = "feedback"
    # 의견 알림(Grafana)이 매분 "최근 10분"을 찾는다.
    __table_args__ = (Index("idx_feedback_created_at", "created_at"),)

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)

    @staticmethod
    def write(user_id: int | None, content: str, now: datetime) -> "Feedback":
        """앞뒤 공백은 여기서 턴다 — 저장된 뒤에 털면 알림 문구와 저장값이 어긋난다."""
        return Feedback(user_id=user_id, content=content.strip(), created_at=now)
