"""종목별 일자별 투자자·기관 순매매 (ka10059). 응답 단위는 백만원(amt_qty_tp=1).

키움 거래소 구분은 종목코드 접미사로 한다:
  "005930" → SOR 통합(KRX+NXT) / "005930_NX" → NXT 단독 / "005930_AL" → 통합의 별칭
"""

import logging
from dataclasses import dataclass

from backend.library.time import today
from backend.platform.kiwoom import client
from backend.platform.kiwoom.client import parse_signed_int

log = logging.getLogger(__name__)

_STOCK_INFO_URL = "/api/dostk/stkinfo"


@dataclass(frozen=True)
class InvestorTrendDay:
    """하루치 순매수(백만원). 양수=순매수, 음수=순매도."""

    date: str  # ISO yyyy-MM-dd
    individual_net: int
    foreign_net: int
    institution_net: int
    other_corp_net: int = 0
    # 기관 세부 — 기관 합계의 내역
    financial_investment_net: int = 0
    insurance_net: int = 0
    other_finance_net: int = 0
    trust_net: int = 0
    private_equity_net: int = 0
    pension_fund_net: int = 0
    bank_net: int = 0


def fetch_investor_trend(stock_code: str) -> list[InvestorTrendDay]:
    """실패해도 빈 목록 — 보조 지표라 호출부를 막지 않는다."""
    try:
        response = client.get_client().post(
            _STOCK_INFO_URL,
            headers=client.query_headers("ka10059"),
            json={
                "dt": today().strftime("%Y%m%d"),
                "stk_cd": stock_code,
                "amt_qty_tp": "1",
                "trde_tp": "0",
                "unit_tp": "1000",
            },
        )
        response.raise_for_status()
        body = response.json()

        code = body.get("return_code")
        if code is not None and code != 0:
            log.error("ka10059 오류 code=%s msg=%s", code, body.get("return_msg"))
            return []

        return [_to_day(item) for item in (body.get("stk_invsr_orgn") or [])]
    except Exception:
        log.error("키움 종목 수급 조회 실패 stk_cd=%s", stock_code, exc_info=True)
        return []


def _to_day(item: dict) -> InvestorTrendDay:
    return InvestorTrendDay(
        date=_to_iso_date(item.get("dt")),
        individual_net=parse_signed_int(item.get("ind_invsr")),
        foreign_net=parse_signed_int(item.get("frgnr_invsr")),
        institution_net=parse_signed_int(item.get("orgn")),
        other_corp_net=parse_signed_int(item.get("etc_corp")),
        financial_investment_net=parse_signed_int(item.get("fnnc_invt")),
        insurance_net=parse_signed_int(item.get("insrnc")),
        other_finance_net=parse_signed_int(item.get("etc_fnnc")),
        trust_net=parse_signed_int(item.get("invtrt")),
        private_equity_net=parse_signed_int(item.get("samo_fund")),
        pension_fund_net=parse_signed_int(item.get("penfnd_etc")),
        bank_net=parse_signed_int(item.get("bank")),
    )


def _to_iso_date(value: str | None) -> str:
    """"20260912" → "2026-09-12". 형식이 어긋나면 빈 문자열."""
    s = (value or "").strip()
    if len(s) != 8:
        return ""
    return f"{s[0:4]}-{s[4:6]}-{s[6:8]}"
