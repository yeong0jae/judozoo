"""업종별 투자자 순매수 (ka10051).

코스피/코스닥 "종합" 업종 행에서 외국인·기관·개인 당일 누적 순매수를 읽는다.
amt_qty_tp=0(금액) 기준이라 순매수 단위는 **억원**, stex_tp=3(통합)으로 KRX+NXT를 합산한다.
"""

import logging
from dataclasses import dataclass

from backend.library.cache import ttl_cache
from backend.platform.kiwoom import client
from backend.platform.kiwoom.client import parse_signed_int

log = logging.getLogger(__name__)

_SECT_URL = "/api/dostk/sect"


@dataclass(frozen=True)
class SectorInvestorNetBuy:
    """한 시장의 투자자별 당일 누적 순매수(억원, 양수=순매수) + 기관 세부 + 지수값/등락률."""

    foreign_eok: int
    institution_eok: int
    individual_eok: int
    other_corp_eok: int
    # 기관 세부 (기관계 ≈ 아래 합계)
    financial_investment_eok: int
    trust_eok: int
    pension_fund_eok: int
    private_equity_eok: int
    insurance_eok: int
    bank_eok: int
    other_finance_eok: int
    index_value: float
    change_rate: float


@ttl_cache(
    "sectorNetBuy",
    ttl_seconds=30,
    maxsize=2,
    key=lambda mrkt_tp, base_dt=None: f"{mrkt_tp}|{base_dt or ''}",
    skip_if=lambda r: r is None,
)
def fetch_sector_net_buy(mrkt_tp: str, base_dt: str | None = None) -> SectorInvestorNetBuy | None:
    """`mrkt_tp` 0=코스피, 1=코스닥. `base_dt`(YYYYMMDD)가 None이면 당일 누적(라이브)."""
    try:
        payload = {
            "mrkt_tp": mrkt_tp,
            "amt_qty_tp": "0",  # 0=금액(억원)
            "stex_tp": "3",     # 3=통합(KRX+NXT)
        }
        if base_dt is not None:
            payload["base_dt"] = base_dt

        response = client.get_client().post(
            _SECT_URL, headers=client.query_headers("ka10051"), json=payload
        )
        response.raise_for_status()
        body = response.json()

        code = body.get("return_code")
        if code is not None and code != 0:
            log.error("ka10051 오류 code=%s msg=%s", code, body.get("return_msg"))
            return None

        rows = body.get("inds_netprps") or []
        if not rows:
            return None
        # 첫 행이 "종합(KOSPI)"/"종합(KOSDAQ)" — 없으면 첫 행으로 폴백.
        total = next((r for r in rows if "종합" in (r.get("inds_nm") or "")), rows[0])

        return SectorInvestorNetBuy(
            foreign_eok=parse_signed_int(total.get("frgnr_netprps")),
            institution_eok=parse_signed_int(total.get("orgn_netprps")),
            individual_eok=parse_signed_int(total.get("ind_netprps")),
            other_corp_eok=parse_signed_int(total.get("etc_corp_netprps")),
            financial_investment_eok=parse_signed_int(total.get("sc_netprps")),
            trust_eok=parse_signed_int(total.get("invtrt_netprps")),
            pension_fund_eok=parse_signed_int(total.get("endw_netprps")),
            private_equity_eok=parse_signed_int(total.get("samo_fund_netprps")),
            insurance_eok=parse_signed_int(total.get("insrnc_netprps")),
            bank_eok=parse_signed_int(total.get("bank_netprps")),
            other_finance_eok=parse_signed_int(total.get("jnsinkm_netprps")),
            # ka10051의 cur_prc/flu_rt는 **소수점이 빠진 정수(×100)**다 —
            # 2653.81이 "+265381", 3.52%가 "352". 100으로 나눠야 한다.
            index_value=abs(parse_signed_int(total.get("cur_prc"))) / 100.0,
            change_rate=parse_signed_int(total.get("flu_rt")) / 100.0,
        )
    except Exception:
        log.error("키움 업종 투자자 순매수 조회 실패 mrkt_tp=%s", mrkt_tp, exc_info=True)
        return None
