"""토스 장 운영 정보 — 국내(KR)·해외(US) 시장 캘린더.

응답은 전일/당일/익일 3영업일을 준다. 개장 여부만 필요하므로 당일 세션 유무로 판정한다.
  KR: `today.integrated` 아래 세션이 있으면 개장
  US: `today`의 4세션(day/pre/regular/after)이 전부 비면 휴장
모든 시간은 KST(+09:00), date는 해당 시장의 **현지 날짜** 기준.
"""

import logging
from datetime import date

from backend.platform.toss import client

log = logging.getLogger(__name__)

_US_SESSIONS = ("dayMarket", "preMarket", "regularMarket", "afterMarket")
_KR_SESSIONS = ("regularMarket", "preMarket", "afterMarket")


def is_trading_day(region: str, on: date) -> bool | None:
    """`region` "KR"/"US". 개장일이면 True, 휴장이면 False, 조회 실패면 None."""
    try:
        return client.with_token_retry(lambda: _fetch(region, on))
    except Exception:
        log.error("토스 장 운영 정보 조회 실패 region=%s date=%s", region, on, exc_info=True)
        return None


def _fetch(region: str, on: date) -> bool | None:
    response = client.get_client().get(
        f"/api/v1/market-calendar/{region}",
        params={"date": on.isoformat()},
        headers=client.auth_headers(),
    )
    response.raise_for_status()
    today = ((response.json().get("result") or {}).get("today")) or {}
    if not today:
        return None
    return _is_open(today)


def _is_open(day: dict) -> bool:
    """KR은 통합 정규장, US는 4세션 중 하나라도 시작시각이 있으면 개장."""
    integrated = day.get("integrated") or {}
    if any((integrated.get(s) or {}).get("startTime") for s in _KR_SESSIONS):
        return True
    return any((day.get(s) or {}).get("startTime") for s in _US_SESSIONS)
