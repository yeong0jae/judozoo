"""종목 관련 뉴스·공시 — KIS 종합 시황/공시. (시황분석 종목 상세용)"""

from datetime import datetime

from fastapi import APIRouter, Query
from pydantic import BaseModel, Field

from backend.library.web import ApiResponse
from backend.news import application
from backend.news.domain import StockNews

router = APIRouter()


class NewsItem(BaseModel):
    """필드명은 기존 API 계약 그대로여야 한다 — 프론트가 이 이름으로 읽는다."""

    seq_no: str = Field(serialization_alias="seqNo")
    title: str
    source: str  # 언론사명 또는 "공시"
    disclosure: bool
    published_at: datetime = Field(serialization_alias="publishedAt")


@router.get("/api/news/stock/{stock_code}")
def stock_news(
    stock_code: str,
    exchange: str | None = Query(default=None),
) -> ApiResponse[list[NewsItem]]:
    """[exchange](NAS/NYS/AMS)를 주면 해외 종목 뉴스, 없으면 국내 종목 뉴스·공시."""
    news = (
        application.stock_news(stock_code)
        if not exchange or not exchange.strip()
        else application.overseas_stock_news(exchange, stock_code)
    )
    return ApiResponse.ok([_to_item(n) for n in news])


def _to_item(news: StockNews) -> NewsItem:
    return NewsItem(
        seq_no=news.seq_no,
        title=news.title,
        source=news.source,
        disclosure=news.disclosure,
        published_at=news.published_at,
    )
