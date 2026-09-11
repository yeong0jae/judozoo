"""사용자가 직접 큐레이션하는 관심 테마.

키움이 분류하는 `theme` 패키지의 테마와는 별개다. 종목은 추가한 순서대로 보여준다.
"""

from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, Integer, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship

from backend.library.db import Base


class WatchThemeStock(Base):
    """관심 테마에 담긴 종목.

    종목명은 담을 당시 이름을 그대로 둔다(개명돼도 카탈로그로 다시 맞출 수 있다).
    """

    __tablename__ = "watch_theme_stock"
    __table_args__ = (UniqueConstraint("theme_id", "stock_code", name="uk_watch_theme_stock"),)

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    theme_id: Mapped[int] = mapped_column(ForeignKey("watch_theme.id"), nullable=False)
    stock_code: Mapped[str] = mapped_column(String(12), nullable=False)
    stock_name: Mapped[str] = mapped_column(String(100), nullable=False)
    # 해외 거래소(NAS/NYS/AMS). None이면 국내 종목 — 시세 조회 경로가 갈린다.
    exchange: Mapped[str | None] = mapped_column(String(3), nullable=True)
    sort_order: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)

    theme: Mapped["WatchTheme"] = relationship(back_populates="stock_list")


class WatchTheme(Base):
    __tablename__ = "watch_theme"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    name: Mapped[str] = mapped_column(String(40), nullable=False)
    sort_order: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)

    stock_list: Mapped[list[WatchThemeStock]] = relationship(
        back_populates="theme",
        cascade="all, delete-orphan",
        order_by=WatchThemeStock.sort_order,
        lazy="selectin",
    )

    @property
    def stocks(self) -> list[WatchThemeStock]:
        return list(self.stock_list)

    def add_stock(self, stock_code: str, stock_name: str, exchange: str | None, at: datetime) -> None:
        """이미 담긴 종목이면 무시한다(중복 추가 방지). `exchange`가 None이면 국내 종목."""
        if any(s.stock_code == stock_code and s.exchange == exchange for s in self.stock_list):
            return
        next_order = max((s.sort_order for s in self.stock_list), default=-1) + 1
        self.stock_list.append(
            WatchThemeStock(
                stock_code=stock_code,
                stock_name=stock_name,
                exchange=exchange,
                sort_order=next_order,
                created_at=at,
                updated_at=at,
            )
        )

    def remove_stock(self, stock_code: str) -> None:
        for stock in [s for s in self.stock_list if s.stock_code == stock_code]:
            self.stock_list.remove(stock)

    def reorder_stocks(self, ordered_codes: list[str]) -> None:
        """`ordered_codes` 순서대로 재배치. 목록에 없는 코드는 무시하고, 빠진 종목은 뒤에 남는다."""
        rank = {code: i for i, code in enumerate(ordered_codes)}
        ordered = sorted(self.stock_list, key=lambda s: rank.get(s.stock_code, len(rank) + 10**9))
        for i, stock in enumerate(ordered):
            stock.sort_order = i
        self.stock_list.sort(key=lambda s: s.sort_order)
