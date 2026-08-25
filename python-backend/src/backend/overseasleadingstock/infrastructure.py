"""해외지수 마감 스냅샷 테이블.

Kotlin이 `ddl-auto=update`로 이미 만든 테이블에 그대로 붙는다. 스키마를 바꾸지 않는다.
`created_at`/`updated_at`은 Kotlin `BaseEntity`가 넣는 감사 컬럼이라 NOT NULL이다.
"""

from datetime import date, datetime

from sqlalchemy import Date, DateTime, Float, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from backend.library.db import Base


class OverseasIndexCloseSnapshot(Base):
    __tablename__ = "overseas_index_close_snapshot"
    __table_args__ = (
        UniqueConstraint("code", "trade_date", name="uk_overseas_index_close_snapshot"),
    )

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    code: Mapped[str] = mapped_column(String(255), nullable=False)
    name: Mapped[str] = mapped_column(String(255), nullable=False)
    trade_date: Mapped[date] = mapped_column(Date, nullable=False)
    captured_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    index_value: Mapped[float] = mapped_column(Float, nullable=False)
    change_rate: Mapped[float] = mapped_column(Float, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
