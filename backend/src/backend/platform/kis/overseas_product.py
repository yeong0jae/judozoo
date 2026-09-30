"""KIS 상품기본정보 (CTPF1702R) — 해외 종목 시가총액."""

import logging
from datetime import date
from time import sleep
from typing import Any
from zoneinfo import ZoneInfo

from backend.library.cache import ttl_cache
from backend.library.time import KST, now
from backend.platform.kis.client import auth_headers, get_client

log = logging.getLogger(__name__)

# 거래소별 상품유형코드. 지원하지 않는 거래소는 호출 자체가 잘못된 것이라 예외를 올린다.
_PRDT_TYPE_CD = {"NAS": "512", "NYS": "513", "AMS": "529"}

#: KIS 초당 거래건수 초과. HTTP 500에 실려 온다 — 잠깐 쉬었다 한 번 더 부르면 대개 지나간다.
_RATE_LIMITED = "EGW00201"
_US_EASTERN = ZoneInfo("America/New_York")


def _us_date() -> date:
    """해외 시총 캐시는 한국 날짜가 아닌 미국 현지 날짜에 맞춰 교체한다."""
    return now().replace(tzinfo=KST).astimezone(_US_EASTERN).date()


@ttl_cache(
    "kisOverseasMarketCap", ttl_seconds=24 * 60 * 60, maxsize=100,
    key=lambda excd, symb: (excd, symb, _us_date()), skip_if=lambda v: v is None,
)
def fetch_market_cap(excd: str, symb: str) -> int | None:
    """시가총액(달러) = 상장주식수 × 현재가. 데이터 없거나 KIS가 실패하면 None.

    종목별·미국 현지 날짜별로 하루 보관한다. 조회 실패는 보관하지 않는다.

    **실패해도 예외를 올리지 않는다.** 시가총액은 상세 화면의 필터 하나일 뿐이라 "조회 불가"로 두면 된다
    (2026-09-30 META — KIS가 HTTP 500을 주자 상세 화면 전체가 500이 됐다). KIS는 한도 초과도 HTTP 500으로
    주므로 본문의 `msg_cd`를 읽어 남기고, 한도 초과면 1초 쉬고 한 번 더 부른다.
    """
    prdt_type_cd = _PRDT_TYPE_CD.get(excd)
    if prdt_type_cd is None:
        raise ValueError(f"지원하지 않는 거래소: {excd}")

    for attempt in (1, 2):
        try:
            response = get_client().get(
                "/uapi/overseas-price/v1/quotations/search-info",
                params={"PRDT_TYPE_CD": prdt_type_cd, "PDNO": symb},
                headers=auth_headers("CTPF1702R"),
            )
            body = response.json()
        except Exception:
            log.warning("KIS 상품기본정보 조회 실패 (%s:%s)", excd, symb, exc_info=True)
            return None
        if response.status_code == 200 and body.get("rt_cd") == "0":
            break
        msg_cd, msg1 = body.get("msg_cd"), body.get("msg1")
        if msg_cd == _RATE_LIMITED and attempt == 1:
            log.warning("KIS 상품기본정보 한도 초과 — 1초 뒤 다시 (%s:%s)", excd, symb)
            sleep(1.0)
            continue
        log.warning("KIS 상품기본정보 오류: HTTP %s %s %s (%s:%s)", response.status_code, msg_cd, msg1, excd, symb)
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
