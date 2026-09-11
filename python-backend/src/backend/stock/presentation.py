"""종목 검색 API."""

from fastapi import APIRouter, Query
from pydantic import BaseModel, Field

from backend.library.web import ApiResponse
from backend.stock import application

router = APIRouter(prefix="/api/stocks")


class StockSearchItem(BaseModel):
    stock_code: str = Field(serialization_alias="stockCode")
    stock_name: str = Field(serialization_alias="stockName")
    exchange: str | None


@router.get("/search")
def search(q: str = Query()) -> ApiResponse[list[StockSearchItem]]:
    """종목명·코드로 검색. 국내 + 해외(NAS/NYS/AMS) 통합, 최대 20건."""
    return ApiResponse.ok(
        [
            StockSearchItem(stock_code=r.stock_code, stock_name=r.stock_name, exchange=r.exchange)
            for r in application.search(q)
        ]
    )
