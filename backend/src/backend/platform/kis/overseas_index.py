"""해외지수 기간별시세 (FHKST03030100).

`FID_COND_MRKT_DIV_CODE=N`(해외지수)로 나스닥종합(COMP) 등 지수의 종가·등락률을 읽는다.
마감 후엔 종가시세가 내려오므로 장 마감 스냅샷에 쓴다.
"""

import logging
from dataclasses import dataclass
from datetime import date
from typing import Any

from backend.platform.kis.client import auth_headers, get_client

log = logging.getLogger(__name__)

_DATE_FMT = "%Y%m%d"


@dataclass(frozen=True)
class OverseasIndexQuote:
    """해외지수 한 종목의 종가·등락률(부호 포함, %) + 실제 영업일."""

    code: str
    name: str
    price: float
    change_rate: float
    trade_date: date


def fetch_index_daily_close(iscd: str, from_: date, to: date) -> OverseasIndexQuote | None:
    """[iscd] 해외지수 마스터 코드(예: 나스닥종합 "COMP"). 구간은 미국 영업일 기준.

    실패·응답오류·파싱불가 시 None — 캡처가 스킵하도록.
    """
    try:
        response = get_client().get(
            "/uapi/overseas-price/v1/quotations/inquire-daily-chartprice",
            params={
                "FID_COND_MRKT_DIV_CODE": "N",
                "FID_INPUT_ISCD": iscd,
                "FID_INPUT_DATE_1": from_.strftime(_DATE_FMT),
                "FID_INPUT_DATE_2": to.strftime(_DATE_FMT),
                "FID_PERIOD_DIV_CODE": "D",
            },
            headers=auth_headers("FHKST03030100"),
        )
        response.raise_for_status()
        body = response.json()
    except Exception:
        log.exception("KIS 해외지수 조회 실패 (iscd=%s)", iscd)
        return None

    if body.get("rt_cd") != "0":
        log.error(
            "KIS 해외지수 오류: code=%s, msg=%s (iscd=%s)",
            body.get("msg_cd"), body.get("msg1"), iscd,
        )
        return None

    output1 = body.get("output1")
    if not output1:
        return None
    price = _to_float(output1.get("ovrs_nmix_prpr"))
    if price is None:
        return None

    # prdy_ctrt(전일대비율)는 응답에 따라 부호가 붙어 오기도 해 **크기만** 쓴다.
    # 방향은 현재가·전일종가 비교로 확정한다(부호코드·부호유무 관례에 의존하지 않음).
    prev_close = _to_float(output1.get("ovrs_nmix_prdy_clpr"))
    magnitude = abs(_to_float(output1.get("prdy_ctrt")) or 0.0)
    change_rate = -magnitude if prev_close is not None and price < prev_close else magnitude

    # 실제 영업일은 일자별(output2)의 최신 stck_bsop_date —
    # 미국 휴장일에도 종가가 찍힌 진짜 날짜를 쓴다.
    dates = [
        d for d in (_to_date(row.get("stck_bsop_date")) for row in (body.get("output2") or []))
        if d is not None
    ]
    trade_date = max(dates) if dates else to

    name = (output1.get("hts_kor_isnm") or "").strip() or iscd
    return OverseasIndexQuote(
        code=iscd,
        name=name,
        price=price,
        change_rate=change_rate,
        trade_date=trade_date,
    )


def _to_float(value: Any) -> float | None:
    try:
        return float(str(value).strip())
    except (TypeError, ValueError):
        return None


def _to_date(value: Any) -> date | None:
    from datetime import datetime

    try:
        return datetime.strptime(str(value).strip(), _DATE_FMT).date()
    except (TypeError, ValueError):
        return None
