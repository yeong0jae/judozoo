"""관심 테마 API — 사용자가 직접 만드는 테마·종목 목록. (시황분석 좌측 패널)"""

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from backend.library.db import get_db
from backend.library.web import ApiResponse
from backend.watchlist import application

router = APIRouter(prefix="/api/watch-themes")


class WatchStockItem(BaseModel):
    stock_code: str = Field(serialization_alias="stockCode")
    stock_name: str = Field(serialization_alias="stockName")
    exchange: str | None


class WatchThemeItem(BaseModel):
    id: int
    name: str
    stocks: list[WatchStockItem]


class StockQuoteItem(BaseModel):
    stock_code: str = Field(serialization_alias="stockCode")
    stock_name: str = Field(serialization_alias="stockName")
    current_price: float = Field(serialization_alias="currentPrice")
    price_change_rate: float = Field(serialization_alias="priceChangeRate")
    overseas: bool


class CreateThemeRequest(BaseModel):
    name: str


class RenameThemeRequest(BaseModel):
    name: str


class ReorderThemesRequest(BaseModel):
    themeIds: list[int]  # noqa: N815 — 기존 API 계약


class ReorderStocksRequest(BaseModel):
    stockCodes: list[str]  # noqa: N815 — 기존 API 계약


class AddStockRequest(BaseModel):
    stockCode: str  # noqa: N815
    stockName: str  # noqa: N815
    exchange: str | None = None


def _to_item(view) -> WatchThemeItem:
    return WatchThemeItem(
        id=view.id,
        name=view.name,
        stocks=[
            WatchStockItem(stock_code=s.stock_code, stock_name=s.stock_name, exchange=s.exchange)
            for s in view.stocks
        ],
    )


@router.get("")
def find_all(db: Session = Depends(get_db)) -> ApiResponse[list[WatchThemeItem]]:
    return ApiResponse.ok([_to_item(v) for v in application.find_all(db)])


@router.post("")
def create_theme(req: CreateThemeRequest, db: Session = Depends(get_db)) -> ApiResponse[WatchThemeItem]:
    return ApiResponse.ok(_to_item(application.create_theme(db, req.name)))


# `/order`는 `/{theme_id}`보다 **먼저** 선언해야 한다 — FastAPI는 선언 순서로 매칭하므로
# 뒤에 두면 "order"가 theme_id로 들어가 422가 난다. Spring은 리터럴 경로를 우선해 문제가 없었다.
@router.patch("/order")
def reorder_themes(req: ReorderThemesRequest, db: Session = Depends(get_db)) -> ApiResponse[None]:
    application.reorder_themes(db, req.themeIds)
    return ApiResponse.ok(None)


@router.patch("/{theme_id}")
def rename_theme(
    theme_id: int, req: RenameThemeRequest, db: Session = Depends(get_db)
) -> ApiResponse[WatchThemeItem]:
    return ApiResponse.ok(_to_item(application.rename_theme(db, theme_id, req.name)))


@router.delete("/{theme_id}")
def delete_theme(theme_id: int, db: Session = Depends(get_db)) -> ApiResponse[None]:
    application.delete_theme(db, theme_id)
    return ApiResponse.ok(None)


@router.post("/{theme_id}/stocks")
def add_stock(
    theme_id: int, req: AddStockRequest, db: Session = Depends(get_db)
) -> ApiResponse[WatchThemeItem]:
    return ApiResponse.ok(
        _to_item(application.add_stock(db, theme_id, req.stockCode, req.stockName, req.exchange))
    )


@router.patch("/{theme_id}/stocks/order")
def reorder_stocks(
    theme_id: int, req: ReorderStocksRequest, db: Session = Depends(get_db)
) -> ApiResponse[WatchThemeItem]:
    return ApiResponse.ok(_to_item(application.reorder_stocks(db, theme_id, req.stockCodes)))


@router.delete("/{theme_id}/stocks/{stock_code}")
def remove_stock(
    theme_id: int, stock_code: str, db: Session = Depends(get_db)
) -> ApiResponse[WatchThemeItem]:
    return ApiResponse.ok(_to_item(application.remove_stock(db, theme_id, stock_code)))


@router.get("/{theme_id}/quotes")
def quotes(theme_id: int, db: Session = Depends(get_db)) -> ApiResponse[list[StockQuoteItem]]:
    """선택한 테마의 종목 시세 — 국내/해외는 서버가 거래소로 갈라 조회한다."""
    return ApiResponse.ok(
        [
            StockQuoteItem(
                stock_code=q.stock_code,
                stock_name=q.stock_name,
                current_price=q.current_price,
                price_change_rate=q.price_change_rate,
                overseas=q.overseas,
            )
            for q in application.quotes_of(db, theme_id)
        ]
    )
