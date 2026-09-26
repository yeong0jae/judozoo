"""주도주 타임라인 도메인 — 어느 분을 찍는가.

외부 의존 없음. 시각은 **각 시장의 현지 시각**(국내 KST, 해외 뉴욕)으로 받는다.
"""

from datetime import time

from backend.market.calendar import Region

#: 찍는 시간 — 홈 풀이 도는 세션과 같다. 끝 시각은 찍지 않는다(국내 19:59, 해외 15:59가 마지막 분)
_WINDOWS = {
    Region.KR: (time(8, 0), time(20, 0)),
    Region.US: (time(4, 0), time(16, 0)),
}

#: 국내 쉬는 구간 — 프리마켓이 끝나고 정규장이 열리기 전, 정규장이 닫히고 애프터마켓이 열리기 전.
#: 단일가·장 전환 구간이라 순위가 멈춰 있거나 뜻이 흐리다. 해외는 프리마켓에서 정규장으로 쉬지 않고 이어진다
_GAPS = {
    Region.KR: ((time(8, 50), time(9, 0)), (time(15, 30), time(15, 40))),
    Region.US: (),
}


def captures(region: Region, at: time) -> bool:
    """현지 시각 `at`의 분을 찍는가 — 세션 안이고 쉬는 구간이 아니면."""
    start, end = _WINDOWS[region]
    if not (start <= at < end):
        return False
    return not any(a <= at < b for a, b in _GAPS[region])
