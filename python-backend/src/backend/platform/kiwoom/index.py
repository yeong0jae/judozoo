"""키움 업종 지수 (ka20001). 코드: 001=종합(KOSPI), 101=종합(KOSDAQ).

`cur_prc` 앞 부호는 등락 **방향 표식**이다 — 지수 레벨은 음수일 수 없으므로 절댓값으로 읽는다.
등락률은 부호가 의미를 가지므로 그대로 둔다.
"""

import logging
from dataclasses import dataclass
from datetime import date, datetime, time

from backend.leadingstock.domain import IndexTick
from backend.library.time import today
from backend.platform.kiwoom import client
from backend.platform.kiwoom.client import parse_signed_float
from backend.stock.domain import Market

log = logging.getLogger(__name__)

_SECT_URL = "/api/dostk/sect"

# 시장 → (업종코드, 시장구분). mrkt_tp 0=코스피, 1=코스닥.
_MARKET_CODES = {Market.KOSPI: ("001", "0"), Market.KOSDAQ: ("101", "1")}


@dataclass(frozen=True)
class IndexSnapshot:
    """지수 한 시점. 클라이언트 응답 구조 변경에서 격리한다."""

    inds_cd: str
    current_value: float
    change_rate: float


@dataclass(frozen=True)
class IndexIntraday:
    """현재 지수값/등락률 + 당일 10초 틱."""

    value: float
    change_rate: float
    ticks: list[IndexTick]


def _fetch(inds_cd: str, mrkt_tp: str) -> dict | None:
    response = client.get_client().post(
        _SECT_URL,
        headers=client.query_headers("ka20001"),
        json={"mrkt_tp": mrkt_tp, "inds_cd": inds_cd},
    )
    response.raise_for_status()
    body = response.json()
    code = body.get("return_code")
    if code is not None and code != 0:
        log.error("ka20001 오류 code=%s msg=%s", code, body.get("return_msg"))
        return None
    return body


def fetch_index(inds_cd: str, mrkt_tp: str = "0") -> IndexSnapshot | None:
    """업종 현재가. 실패 시 None."""
    try:
        log.info("키움 업종 지수 조회 mrkt_tp=%s inds_cd=%s", mrkt_tp, inds_cd)
        body = _fetch(inds_cd, mrkt_tp)
        if body is None:
            return None
        return IndexSnapshot(
            inds_cd=inds_cd,
            current_value=abs(parse_signed_float(body.get("cur_prc"))),
            change_rate=parse_signed_float(body.get("flu_rt")),
        )
    except Exception:
        log.error("키움 업종 지수 조회 실패 inds_cd=%s", inds_cd, exc_info=True)
        return None


def fetch_index_intraday_for(market: Market, on: date | None = None) -> IndexIntraday | None:
    """시장별 종합 지수의 당일 인트라데이."""
    inds_cd, mrkt_tp = _MARKET_CODES[market]
    return fetch_index_intraday(inds_cd, mrkt_tp, on)


def fetch_index_intraday(inds_cd: str, mrkt_tp: str, on: date | None = None) -> IndexIntraday | None:
    """상단 현재 지수값/등락률 + 장중 10초 틱(`inds_cur_prc_tm`, 시간 오름차순).

    틱 시각은 `tm_n`(HHmmss)에 조회 기준일을 붙여 만든다.
    """
    day = on or today()
    try:
        body = _fetch(inds_cd, mrkt_tp)
        if body is None:
            return None
        ticks = [t for t in (_parse_tick(i, day) for i in (body.get("inds_cur_prc_tm") or [])) if t]
        ticks.sort(key=lambda t: t.at)
        return IndexIntraday(
            value=abs(parse_signed_float(body.get("cur_prc"))),
            change_rate=parse_signed_float(body.get("flu_rt")),
            ticks=ticks,
        )
    except Exception:
        log.error("키움 업종 인트라데이 조회 실패 inds_cd=%s", inds_cd, exc_info=True)
        return None


def _parse_tick(item: dict, day: date) -> IndexTick | None:
    at = _parse_time(item.get("tm_n"))
    if at is None:
        return None
    return IndexTick(
        at=datetime.combine(day, at),
        value=abs(parse_signed_float(item.get("cur_prc_n"))),
        volume=int(parse_signed_float(item.get("trde_qty_n"))),
    )


def _parse_time(value: str | None) -> time | None:
    """"143000"(HHmmss) → time. 형식이 어긋나면 None."""
    s = (value or "").strip()
    if len(s) != 6:
        return None
    try:
        return time(int(s[0:2]), int(s[2:4]), int(s[4:6]))
    except ValueError:
        return None
