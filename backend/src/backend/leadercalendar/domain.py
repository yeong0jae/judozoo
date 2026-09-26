"""주도주 캘린더 도메인 — 국내 날짜와 해외장을 짝짓는 규칙.

외부 의존 없음.
"""

from datetime import date, timedelta


def previous_weekday(on: date) -> date:
    """`on` 직전 평일. 국내 날짜 D의 칸에는 이 날짜의 해외장이 붙는다 — 월요일이면 금요일 장.

    휴장은 따지지 않는다. 그날 해외장이 쉬었으면 기록이 없을 뿐이다.
    """
    day = on - timedelta(days=1)
    while day.weekday() >= 5:
        day -= timedelta(days=1)
    return day
