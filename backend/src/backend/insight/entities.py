"""왜 오르나 엔티티 — **생성할 때마다 한 행**. 이력은 이 테이블 자체다.

화면은 (시장, 종목, 거래일)마다 `domain.shown`이 고른 한 행만 읽는다. 30분 규칙·재시도·하루 상한도
행만 보고 판단한다 — 카운터나 상태 열을 따로 두지 않는다(026 §설계 결정).
"""

from datetime import date, datetime

from sqlalchemy import JSON, BigInteger, Boolean, Date, DateTime, Enum, Index, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from backend.insight.domain import Attempt, Source, Trigger, Verdict
from backend.library.db import Base
from backend.market.calendar import Region


class StockReason(Base):
    __tablename__ = "stock_reason"
    __table_args__ = (
        Index("idx_stock_reason_day", "region", "trading_day", "code", "generated_at"),
        Index("idx_stock_reason_generated", "generated_at"),
    )

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    region: Mapped[Region] = mapped_column(Enum(Region, length=2), nullable=False)
    #: 현지 거래일 — US는 뉴욕 기준
    trading_day: Mapped[date] = mapped_column(Date, nullable=False)
    #: KR 6자리 단축코드 / US 심볼
    code: Mapped[str] = mapped_column(String(16), nullable=False)
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    #: KST. 화면의 "기준" 시각
    generated_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    trigger: Mapped[str] = mapped_column(String(10), nullable=False)
    #: 거절되면 False — 화면에 안 쓰이고, 3분 뒤 한 번 다시 만든다
    published: Mapped[bool] = mapped_column(Boolean, nullable=False)
    explained: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    keywords: Mapped[list] = mapped_column(JSON, nullable=False)
    reason: Mapped[str] = mapped_column(String(200), nullable=False, default="")
    #: [{source, title, url}] 최대 2
    evidence: Mapped[list] = mapped_column(JSON, nullable=False)
    related: Mapped[list] = mapped_column(JSON, nullable=False)
    model: Mapped[str] = mapped_column(String(40), nullable=False)
    error: Mapped[str | None] = mapped_column(Text, nullable=True)

    @classmethod
    def made(
        cls, region: Region, trading_day: date, code: str, name: str, at: datetime,
        trigger: Trigger, verdict: Verdict, model: str,
    ) -> "StockReason":
        return cls(
            region=region, trading_day=trading_day, code=code, name=name, generated_at=at,
            trigger=trigger.value, published=verdict.published, explained=verdict.explained,
            keywords=verdict.keywords, reason=verdict.reason[:200],
            evidence=[_article(s) for s in verdict.evidence], related=[_article(s) for s in verdict.related],
            model=model, error=verdict.error,
        )

    def attempt(self, local_at: datetime) -> Attempt:
        """30분·재시도 판단용. 시각은 부르는 쪽이 시장 현지 시각으로 바꿔 준다."""
        return Attempt(at=local_at, published=self.published, retry=self.trigger == Trigger.RETRY.value)


def _article(s: Source) -> dict:
    return {"source": s.domain, "title": s.title, "url": s.url}
