"""키움 프로그램 매매 — 종목 일별(ka90013).

금액 단위는 백만원(amt_qty_tp=1).
"""

import logging
from dataclasses import dataclass

from backend.library.time import today
from backend.platform.kiwoom import client

log = logging.getLogger(__name__)

_MRKCOND_URL = "/api/dostk/mrkcond"


@dataclass(frozen=True)
class ProgramTradingData:
    stock_code: str
    date: str
    program_sell_amount: int
    program_buy_amount: int
    program_net_buy_amount: int


def parse_amount(value: str | None) -> int:
    """키움이 음수를 내는 변종을 전부 흡수한다 — `"-123"`, `"123-"`, `"--123"`.

    `"--123"`이 실제로 온다. 앞의 하나만 떼면 부호가 뒤집힌다.
    """
    s = (value or "").strip()
    if not s:
        return 0
    try:
        if s.startswith("--"):
            return -int(s[2:])
        if s.startswith("-"):
            return int(s)
        if s.endswith("-"):
            return -int(s[:-1])
        return int(s)
    except ValueError:
        log.warning("금액 파싱 실패: %s", value)
        return 0


def fetch_program_trading(stock_code: str) -> ProgramTradingData | None:
    """종목 일별 프로그램 매매 추이 (ka90013)."""
    try:
        log.info("키움 종목 프로그램매매 조회 stk_cd=%s", stock_code)
        response = client.get_client().post(
            _MRKCOND_URL,
            headers=client.query_headers("ka90013"),
            json={
                "amt_qty_tp": "1",  # 1:금액
                "stk_cd": stock_code,
                "date": today().strftime("%Y%m%d"),
            },
        )
        response.raise_for_status()
        # 응답에서 통째로 빠지는 경우가 있다 — 운영 로그에서 확인됐다.
        items = response.json().get("stk_daly_prm_trde_trnsn") or []
        if not items:
            return None
        item = items[0]
        return ProgramTradingData(
            stock_code=stock_code,
            date=item.get("dt", ""),
            program_sell_amount=parse_amount(item.get("prm_sell_amt")),
            program_buy_amount=parse_amount(item.get("prm_buy_amt")),
            program_net_buy_amount=parse_amount(item.get("prm_netprps_amt")),
        )
    except Exception:
        log.error("키움 종목 프로그램매매 조회 실패 stk_cd=%s", stock_code, exc_info=True)
        return None


def fetch_program_net_buy(stock_code: str) -> int:
    data = fetch_program_trading(stock_code)
    return data.program_net_buy_amount if data else 0
