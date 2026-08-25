"""KIS 상품기본정보 (CTPF1702R) — 해외 종목 시가총액."""

import logging
from typing import Any

from backend.library.cache import ttl_cache
from backend.platform.kis.client import auth_headers, get_client

log = logging.getLogger(__name__)

# 거래소별 상품유형코드. 지원하지 않는 거래소는 호출 자체가 잘못된 것이라 예외를 올린다.
_PRDT_TYPE_CD = {"NAS": "512", "NYS": "513", "AMS": "529"}


@ttl_cache("kisOverseasMarketCap", ttl_seconds=3600, maxsize=100, skip_if=lambda v: v is None)
def fetch_market_cap(excd: str, symb: str) -> int | None:
    """시가총액(달러) = 상장주식수 × 현재가. 데이터 없으면 None.

    상장주식수는 거의 불변이라 TTL이 길다.
    """
    prdt_type_cd = _PRDT_TYPE_CD.get(excd)
    if prdt_type_cd is None:
        raise ValueError(f"지원하지 않는 거래소: {excd}")

    response = get_client().get(
        "/uapi/overseas-price/v1/quotations/search-info",
        params={"PRDT_TYPE_CD": prdt_type_cd, "PDNO": symb},
        headers=auth_headers("CTPF1702R"),
    )
    response.raise_for_status()
    body = response.json()
    if body.get("rt_cd") != "0":
        log.warning("KIS 상품기본정보 오류: %s (%s:%s)", body.get("msg1"), excd, symb)
        return None

    output = body.get("output")
    if not output:
        return None
    shares = _to_int(output.get("lstg_stck_num"))
    price = _to_float(output.get("ovrs_now_pric1"))
    if shares is None or price is None or shares <= 0 or price <= 0:
        return None
    return int(shares * price)


def _to_int(value: Any) -> int | None:
    try:
        return int(str(value).strip())
    except (TypeError, ValueError):
        return None


def _to_float(value: Any) -> float | None:
    try:
        return float(str(value).strip())
    except (TypeError, ValueError):
        return None
