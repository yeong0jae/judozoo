"""키움 프로그램 매매 — 종목 일별(ka90013), 시장 일자별(ka90010).

금액 단위는 백만원(amt_qty_tp=1).
"""

import logging
from dataclasses import dataclass
from datetime import date, datetime

from backend.library.time import today
from backend.platform.kiwoom import client
from backend.stock.domain import Market

log = logging.getLogger(__name__)

_MRKCOND_URL = "/api/dostk/mrkcond"

# 프로그램 매매 시장구분 — 통합(KRX+NXT) 기준.
_PROGRAM_MRKT_CODES = {Market.KOSPI: "P001_AL01", Market.KOSDAQ: "P101_AL02"}


@dataclass(frozen=True)
class ProgramTradingData:
    stock_code: str
    date: str
    program_sell_amount: int
    program_buy_amount: int
    program_net_buy_amount: int


@dataclass(frozen=True)
class MarketProgramPoint:
    """하루치 프로그램 순매수(백만원). 최신→과거로 들어온다."""

    date: date | None
    arbitrage_net: int      # 차익
    non_arbitrage_net: int  # 비차익
    total_net: int          # 전체


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


def fetch_market_program_daily(market: Market, on: date) -> list[MarketProgramPoint]:
    """시장 일자별 프로그램 매매 추이 (ka90010) — 최근 N일, 최신→과거.

    첫 행(오늘)은 그 시점까지의 **당일 누적**이라, 폴러가 이 값을 스냅샷으로 찍어 세션을 만든다.
    """
    try:
        response = client.get_client().post(
            _MRKCOND_URL,
            headers=client.query_headers("ka90010"),
            json={
                "date": on.strftime("%Y%m%d"),
                "amt_qty_tp": "1",  # 1:금액(백만원)
                "mrkt_tp": _PROGRAM_MRKT_CODES[market],
                "min_tic_tp": "0",
                "stex_tp": "3",     # 3:통합(KRX+NXT)
            },
        )
        response.raise_for_status()
        body = response.json()

        code = body.get("return_code")
        if code is not None and code != 0:
            log.error("ka90010 오류 code=%s msg=%s", code, body.get("return_msg"))
            return []

        return [
            MarketProgramPoint(
                date=_parse_date_prefix(item.get("cntr_tm", "")),
                arbitrage_net=parse_amount(item.get("dfrt_trde_netprps")),
                non_arbitrage_net=parse_amount(item.get("ndiffpro_trde_netprps")),
                total_net=parse_amount(item.get("all_netprps")),
            )
            for item in (body.get("prm_trde_trnsn") or [])
        ]
    except Exception:
        log.error("키움 시장 프로그램매매 조회 실패 market=%s", market, exc_info=True)
        return []


def _parse_date_prefix(value: str) -> date | None:
    """`cntr_tm`은 yyyyMMddHHmmss — 앞 8자리만 쓴다."""
    s = (value or "").strip()[:8]
    if len(s) != 8:
        return None
    try:
        return datetime.strptime(s, "%Y%m%d").date()
    except ValueError:
        return None
