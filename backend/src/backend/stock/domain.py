"""종목 마스터 도메인.

Kotlin `stock.domain`과 1:1. 엔티티가 곧 도메인 객체다 — 검색 판정(`matches`)을
바깥에서 필드를 꺼내 계산하지 않고 객체가 직접 답한다.
테이블은 Kotlin `ddl-auto=update`가 만들어 둔 것에 그대로 붙는다.
"""

import enum
from datetime import datetime

from sqlalchemy import DateTime, Enum, Index, String
from sqlalchemy.orm import Mapped, mapped_column

from backend.library.db import Base


class Market(enum.Enum):
    KOSPI = "KOSPI"
    KOSDAQ = "KOSDAQ"


class Stock(Base):
    """거래 가능한 상장 종목.

    카탈로그 갱신은 항상 전체 교체(delete-all + insert-all)라 모든 row의 `created_at`이
    한 번의 갱신 시각으로 정렬된다 — "오늘 갱신되었는가"는 별도 동기화 테이블 없이
    가장 최근 `created_at` 하나로 판정한다.
    """

    __tablename__ = "stocks"

    short_code: Mapped[str] = mapped_column(String(12), primary_key=True)
    standard_code: Mapped[str] = mapped_column(String(12), nullable=False)
    name: Mapped[str] = mapped_column(String(50), nullable=False)
    market: Mapped[Market] = mapped_column(Enum(Market, length=6), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)

    def matches(self, query: str) -> bool:
        """질의가 종목명에 포함되거나 종목코드 접두사로 일치하면 True. 대소문자 무시."""
        q = query.strip().lower()
        if not q:
            return False
        return q in self.name.lower() or self.short_code.lower().startswith(q)

    def _relevance(self, q: str) -> int:
        if self.name.lower().startswith(q):
            return 0
        if self.short_code.lower().startswith(q):
            return 1
        return 2


class Stocks:
    """종목 카탈로그 전체를 감싸는 1급 컬렉션. 검색 책임을 컬렉션이 직접 가진다."""

    def __init__(self, stocks: list[Stock]) -> None:
        self._stocks = stocks

    def __len__(self) -> int:
        return len(self._stocks)

    def search(self, query: str, limit: int) -> list[Stock]:
        """종목명/코드로 검색. 관련도 순 — 1) 종목명 시작 2) 코드 시작 3) 종목명 포함.

        동일 관련도면 종목명 사전순. 최대 [limit]건.
        """
        q = query.strip().lower()
        if not q:
            return []
        found = [s for s in self._stocks if s.matches(q)]
        found.sort(key=lambda s: (s._relevance(q), s.name))
        return found[:limit]

    def find(self, short_code: str) -> "Stock | None":
        """단축코드로 한 종목. 거래소 접미사(`009150_AL`)가 붙어 와도 앞쪽만 본다 —
        시세 쪽은 SOR 통합 코드를 쓰지만 카탈로그는 단축코드로 적재된다."""
        code = short_code.split("_")[0].strip()
        return next((s for s in self._stocks if s.short_code == code), None)


class OverseasStock(Base):
    """해외 상장 종목(미국).

    국내 `Stock`과 테이블을 나눈 이유: 식별자가 종목코드가 아니라 (거래소, 심볼) 쌍이고,
    시세 조회 경로도 다르다.
    """

    __tablename__ = "overseas_stocks"
    __table_args__ = (Index("idx_overseas_stocks_symbol", "exchange", "symbol"),)

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    exchange: Mapped[str] = mapped_column(String(3), nullable=False)  # NAS / NYS / AMS
    symbol: Mapped[str] = mapped_column(String(16), nullable=False)  # AAPL
    name: Mapped[str] = mapped_column(String(100), nullable=False)  # 애플 (한글명)
    english_name: Mapped[str] = mapped_column(String(120), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)

    def matches(self, query: str) -> bool:
        """종목명(한글·영문)이나 심볼에 질의가 들어가면 매칭. 대소문자 무시."""
        q = query.strip().lower()
        return q in self.name.lower() or q in self.english_name.lower() or q in self.symbol.lower()
