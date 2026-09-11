"""거래일 이슈 메모.

이 앱의 **유일한 사용자 쓰기 데이터** — 시장 데이터(전시용)와 달리 사람이 입력한다.
"""

from datetime import date as date_type
from datetime import datetime

from sqlalchemy import Date, DateTime, Index, String
from sqlalchemy.orm import Mapped, mapped_column

from backend.library.db import Base


class DailyIssue(Base):
    """한 날짜에 여러 건을 붙일 수 있다."""

    __tablename__ = "daily_issue"
    __table_args__ = (Index("idx_daily_issue_date", "trade_date"),)

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    trade_date: Mapped[date_type] = mapped_column(Date, nullable=False)
    content: Mapped[str] = mapped_column(String(500), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)

    def edit(self, content: str) -> None:
        """내용 교체 — 공백만 남는 입력은 호출 전에 걸러진다."""
        self.content = content
