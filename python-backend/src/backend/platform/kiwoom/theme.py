"""종목이 속한 테마명 (ka90001, 종목검색=qry_tp 2)."""

import logging

from backend.library.cache import ttl_cache
from backend.platform.kiwoom import client

log = logging.getLogger(__name__)

_THEME_URL = "/api/dostk/thme"


def _short_code(stock_code: str) -> str:
    """접미사(`_AL`/`_NX`)를 떼고 6자리로. 같은 종목이면 캐시를 맞히게 정규화한다."""
    return stock_code.split("_")[0][:6]


@ttl_cache("stockThemes", ttl_seconds=12 * 3600, maxsize=200, key=lambda stock_code: _short_code(stock_code))
def fetch_themes_for_stock(stock_code: str) -> list[str]:
    """`flu_pl_amt_tp=3`(상위등락률)로 정렬해 그날 강한 테마가 앞에 온다. 실패 시 빈 목록."""
    short = _short_code(stock_code)
    try:
        response = client.get_client().post(
            _THEME_URL,
            headers=client.query_headers("ka90001"),
            json={
                "qry_tp": "2",         # 0:전체, 1:테마, 2:종목검색
                "stk_cd": short,
                "date_tp": "1",        # 등락 기준 n일전
                "thema_nm": "",
                "flu_pl_amt_tp": "3",  # 3:상위등락률
                "stex_tp": "1",        # 1:KRX
            },
        )
        response.raise_for_status()
        body = response.json()

        code = body.get("return_code")
        if code is not None and code != 0:
            log.error("키움 테마 조회 오류 stk=%s code=%s msg=%s", short, code, body.get("return_msg"))
            return []

        return [
            name
            for g in (body.get("thema_grp") or [])
            if (name := (g.get("thema_nm") or "").strip())
        ]
    except Exception:
        log.error("키움 테마 조회 실패 stk=%s", short, exc_info=True)
        return []
