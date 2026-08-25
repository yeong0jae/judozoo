"""KIS 뉴스·공시 조회.

국내: 종합 시황/공시 제목(FHKST01011800) — HTS [0601] 우측 리스트.
해외: 해외뉴스종합(HHPSTH60100C1).

둘 다 **제목만** 주는 API라 원문 링크는 없다.
실패·응답오류 시 빈 리스트를 돌려준다 — 호출측이 빈 목록으로 표시하도록.
"""

import logging
from datetime import datetime
from typing import Any

from backend.news.domain import StockNews
from backend.platform.kis.client import auth_headers, get_client

log = logging.getLogger(__name__)


def fetch_stock_news(stock_code: str) -> list[StockNews]:
    """[stock_code] 관련 뉴스·공시 제목 목록(최신순).

    시장 전체 시황 기사도 해당 종목이 등록돼 있으면 함께 온다.
    종목코드 외 파라미터는 **전부 공백이 필수**다.
    """
    try:
        response = get_client().get(
            "/uapi/domestic-stock/v1/quotations/news-title",
            params={
                "FID_NEWS_OFER_ENTP_CODE": "",
                "FID_COND_MRKT_CLS_CODE": "",
                "FID_INPUT_ISCD": stock_code,
                "FID_TITL_CNTT": "",
                "FID_INPUT_DATE_1": "",
                "FID_INPUT_HOUR_1": "",
                "FID_RANK_SORT_CLS_CODE": "",
                "FID_INPUT_SRNO": "",
            },
            headers=auth_headers("FHKST01011800"),
        )
        response.raise_for_status()
        body = response.json()
        if body.get("rt_cd") != "0":
            log.error("KIS 뉴스 오류: code=%s, msg=%s", body.get("msg_cd"), body.get("msg1"))
            return []
        rows = body.get("output") or []
        return [news for news in (_to_domestic_news(r) for r in rows) if news is not None]
    except Exception:
        log.exception("KIS 뉴스 조회 실패 (stockCode=%s)", stock_code)
        return []


def fetch_overseas_news(exchange: str, symbol: str) -> list[StockNews]:
    """해외(미국) 종목 뉴스 제목 목록(최신순, 10건).

    SYMB만 넘기면 0건이 온다 — 국가코드·거래소코드를 함께 줘야 종목 필터가 걸린다.
    [exchange]는 NAS/NYS/AMS.
    """
    try:
        response = get_client().get(
            "/uapi/overseas-price/v1/quotations/news-title",
            params={
                "INFO_GB": "",
                "CLASS_CD": "",
                "NATION_CD": "US",
                "EXCHANGE_CD": exchange,
                "SYMB": symbol,
                "DATA_DT": "",
                "DATA_TM": "",
                "CTS": "",
            },
            headers=auth_headers("HHPSTH60100C1"),
        )
        response.raise_for_status()
        body = response.json()
        if body.get("rt_cd") != "0":
            log.error("KIS 해외뉴스 오류: code=%s, msg=%s", body.get("msg_cd"), body.get("msg1"))
            return []
        rows = body.get("outblock1") or []
        return [news for news in (_to_overseas_news(r) for r in rows) if news is not None]
    except Exception:
        log.exception("KIS 해외뉴스 조회 실패 (exchange=%s, symbol=%s)", exchange, symbol)
        return []


def _to_domestic_news(row: dict[str, Any]) -> StockNews | None:
    seq_no = _trimmed(row.get("cntt_usiq_srno"))
    title = _trimmed(row.get("hts_pbnt_titl_cntt"))
    published_at = _parse_at(row.get("data_dt"), row.get("data_tm"))
    if not seq_no or not title or published_at is None:
        return None
    return StockNews(
        seq_no=seq_no,
        title=title,
        source=_trimmed(row.get("dorg")) or "",
        provider_code=_trimmed(row.get("news_ofer_entp_code")) or "",
        published_at=published_at,
    )


def _to_overseas_news(row: dict[str, Any]) -> StockNews | None:
    seq_no = _trimmed(row.get("news_key"))
    title = _trimmed(row.get("title"))
    published_at = _parse_at(row.get("data_dt"), row.get("data_tm"))
    if not seq_no or not title or published_at is None:
        return None
    return StockNews(
        seq_no=seq_no,
        title=title,
        source=_trimmed(row.get("source")) or "",
        provider_code="",  # 해외 뉴스엔 공시가 섞이지 않는다
        published_at=published_at,
    )


def _trimmed(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    stripped = value.strip()
    return stripped or None


def _parse_at(date: Any, time: Any) -> datetime | None:
    """작성일자(yyyyMMdd) + 작성시간(HHmmss). 시간은 6자리로 좌측 0 채움."""
    try:
        d = str(date).strip()
        t = str(time).strip().zfill(6)
        return datetime.strptime(f"{d}{t}", "%Y%m%d%H%M%S")
    except (TypeError, ValueError):
        return None
