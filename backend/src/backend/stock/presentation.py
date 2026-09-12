"""종목 검색 · 종목 투자자 수급 API."""

from datetime import date

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


class StockOrgBreakdownItem(BaseModel):
    financial_investment_million: int = Field(serialization_alias="financialInvestmentMillion")
    trust_million: int = Field(serialization_alias="trustMillion")
    pension_fund_million: int = Field(serialization_alias="pensionFundMillion")
    private_equity_million: int = Field(serialization_alias="privateEquityMillion")
    insurance_million: int = Field(serialization_alias="insuranceMillion")
    bank_million: int = Field(serialization_alias="bankMillion")
    other_finance_million: int = Field(serialization_alias="otherFinanceMillion")


class StockInvestorDayItem(BaseModel):
    date: date
    individual_million: int = Field(serialization_alias="individualMillion")
    foreign_million: int = Field(serialization_alias="foreignMillion")
    institution_million: int = Field(serialization_alias="institutionMillion")
    other_corp_million: int = Field(serialization_alias="otherCorpMillion")
    breakdown: StockOrgBreakdownItem


@router.get("/{stock_code}/investor/daily")
def investor_daily(stock_code: str, count: int = Query(10)) -> ApiResponse[list[StockInvestorDayItem]]:
    """최근 N거래일 종목 투자자 순매수(백만원) — 시황분석 종목 상세용."""
    return ApiResponse.ok([
        StockInvestorDayItem(
            date=d.date,
            individual_million=d.individual_million,
            foreign_million=d.foreign_million,
            institution_million=d.institution_million,
            other_corp_million=d.other_corp_million,
            breakdown=StockOrgBreakdownItem(**vars(d.breakdown)),
        )
        for d in application.investor_daily_history(stock_code, count)
    ])
