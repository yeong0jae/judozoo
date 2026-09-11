"""국내 휴장일 조회 (CTCA0903R).

`base_dt`부터 미래로 개장일 여부를 준다. 원장 연동 API라 "1일 1회 호출 권장" —
호출측이 하루 캐시로 감싼다. 실패 시 빈 집합(호출측이 주말만으로 폴백하게).
"""

import logging
from datetime import date, datetime

from backend.platform.kis import client

log = logging.getLogger(__name__)

_URL = "/uapi/domestic-stock/v1/quotations/chk-holiday"


def fetch_open_days(base_dt: date) -> set[date]:
    """`base_dt` 이후 개장일(opnd_yn=Y) 집합. 한 응답에 약 24일치가 온다."""
    try:
        response = client.get_client().get(
            _URL,
            params={"BASS_DT": base_dt.strftime("%Y%m%d"), "CTX_AREA_NK": "", "CTX_AREA_FK": ""},
            headers=client.auth_headers("CTCA0903R"),
        )
        response.raise_for_status()
        body = response.json()

        if body.get("rt_cd") != "0":
            log.error("KIS 휴장일 오류 code=%s msg=%s", body.get("msg_cd"), body.get("msg1"))
            return set()

        days = set()
        for item in body.get("output") or []:
            if (item.get("opnd_yn") or "").strip() != "Y":
                continue
            parsed = _parse_date(item.get("bass_dt"))
            if parsed is not None:
                days.add(parsed)
        return days
    except Exception:
        log.error("KIS 휴장일 조회 실패 base_dt=%s", base_dt, exc_info=True)
        return set()


def _parse_date(value: str | None) -> date | None:
    s = (value or "").strip()
    if len(s) != 8:
        return None
    try:
        return datetime.strptime(s, "%Y%m%d").date()
    except ValueError:
        return None
