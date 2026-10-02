"""왜 오르나 API — 지금 세션의 종목별 사유.

키워드·사유 문장·기준 시각은 공개다(한 줄은 가입 유도). 근거·관련 기사는 로그인 뒤라,
방문자에게는 건수만 내려 "n건은 로그인 후 볼 수 있습니다"를 그리게 한다.
주도주 응답에 끼워 넣지 않는다 — 주도주가 insight에 기대지 않고, 갱신 주기도 다르다(15초와 분).
"""

from datetime import datetime
from typing import Literal

from fastapi import APIRouter, Depends, Query, Request
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from backend.auth.presentation import current_user
from backend.insight import application
from backend.library.db import get_db
from backend.library.time import now
from backend.library.web import ApiResponse
from backend.market.calendar import Region

router = APIRouter(prefix="/api/insight")

_REGIONS = {"kr": Region.KR, "us": Region.US}


class Article(BaseModel):
    source: str
    title: str
    url: str


class StockReasonItem(BaseModel):
    code: str
    #: 뉴스로 설명되지 않으면 False — 목록에는 아무것도 붙이지 않고, 상세에는 관련 기사만
    explained: bool
    keywords: list[str]
    reason: str
    #: KST. 화면의 "기준" 시각
    generated_at: datetime = Field(serialization_alias="generatedAt")
    evidence_count: int = Field(serialization_alias="evidenceCount")
    related_count: int = Field(serialization_alias="relatedCount")
    #: 로그인일 때만
    evidence: list[Article] | None = None
    related: list[Article] | None = None


@router.get("/reasons")
def get_reasons(
    request: Request,
    market: Literal["kr", "us"] = Query(),
    db: Session = Depends(get_db),
) -> ApiResponse[list[StockReasonItem]]:
    member = current_user(request) is not None
    rows = application.reasons(db, _REGIONS[market], now())
    return ApiResponse.ok([
        StockReasonItem(
            code=r.code, explained=r.explained, keywords=r.keywords, reason=r.reason, generated_at=r.generated_at,
            evidence_count=len(r.evidence), related_count=len(r.related),
            evidence=[Article(**a) for a in r.evidence] if member else None,
            related=[Article(**a) for a in r.related] if member else None,
        )
        for r in rows
    ])
