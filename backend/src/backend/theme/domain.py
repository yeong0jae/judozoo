"""테마 캘린더 — 하루치 "강한 테마"와 거기에 돈을 몰아준 종목.

과거 일자별 데이터 API가 없어 **매일 캡처해 누적**하는 구조라 백필은 불가능하다.
"""

from datetime import date as date_type
from datetime import datetime

from sqlalchemy import BigInteger, Date, DateTime, Float, Index, Integer, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from backend.library.db import Base


class ThemeDailyRecord(Base):
    """`rank`는 그날 거래대금 상위 순위(1=가장 많음), `trading_value`는 소속 상위 종목 거래대금 합(원)."""

    __tablename__ = "theme_daily"
    __table_args__ = (
        UniqueConstraint("date", "theme_name"),
        Index("idx_theme_daily_date", "date"),
    )

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    date: Mapped[date_type] = mapped_column(Date, nullable=False)
    # 컬럼명 rank는 MySQL 8.0 예약어(RANK())라 DDL 생성이 조용히 실패한다 → theme_rank로 매핑
    rank: Mapped[int] = mapped_column("theme_rank", Integer, nullable=False)
    theme_name: Mapped[str] = mapped_column(String(255), nullable=False)
    trading_value: Mapped[int] = mapped_column(BigInteger, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)


class ThemeDailyStock(Base):
    """그날 거래대금 상위 종목 중 해당 테마에 속한 것 = 테마에 돈을 몰아준 종목."""

    __tablename__ = "theme_daily_stock"
    __table_args__ = (
        Index("idx_theme_daily_stock_pid", "theme_daily_id"),
        Index("idx_theme_daily_stock_date", "date"),
    )

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    theme_daily_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    date: Mapped[date_type] = mapped_column(Date, nullable=False)
    stock_code: Mapped[str] = mapped_column(String(255), nullable=False)
    stock_name: Mapped[str] = mapped_column(String(255), nullable=False)
    trading_value: Mapped[int] = mapped_column(BigInteger, nullable=False)
    # 캡처 시점 당일 등락률(%). 기능 추가 전 적재분은 None.
    price_change_rate: Mapped[float | None] = mapped_column(Float, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
