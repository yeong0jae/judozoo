"""공개 API 허용목록.

기본이 차단이다 — 여기 없는 `/api` 경로는 로그인을 요구한다.
엔드포인트마다 의존성을 붙이면 새로 추가할 때 빠뜨리기 쉽고, 빠뜨린 쪽이 열린 채로 남는다.
반대로 해두면 빠뜨렸을 때 막히므로 사고가 나도 노출이 아니라 불편으로 끝난다.

공개로 두는 것은 주도주 후보 **목록**, 종목 시그널 **미리보기**, 지수 시세,
코스피 야간 선물이다. 미리보기처럼 일부만 여는 경로는 엔드포인트가 직접 잘라 내려보낸다.
"""

import re

# 경로 파라미터는 하나의 세그먼트만 받는다.
_SEG = r"[^/]+"

_PUBLIC_PATTERNS = [
    # 주도주 후보 **목록만** 공개한다. 종목 상세(일봉·분봉·수급)는 로그인 뒤다.
    r"/api/leading-stocks/candidates",
    # 종목 시그널 — 미리보기로 최신 몇 건만 연다. 자르는 건 엔드포인트가 한다
    # (여기서 열어도 응답 전체가 나가지는 않는다). 지수 시그널은 로그인 뒤다.
    r"/api/leading-stocks/signal-events",
    # 지지·저항도 같은 방식 — 상위 몇 개만 엔드포인트가 잘라 내려보낸다
    r"/api/leading-stocks/breakout-radar",
    r"/api/overseas-leading-stocks/ranking",
    # 휴장 배너
    r"/api/market/calendar/status",
    # 지수 시세 — 어디서나 얻을 수 있는 정보라 가릴 값어치가 없다.
    r"/api/market/kospi",
    r"/api/market/kosdaq",
    # 코스피 야간 선물 — 정규장 밖 유일한 국내 지표라 로그인 없이 연다.
    r"/api/market/futures/night/quote",
    r"/api/market/futures/night/candles",
    # 로그인 흐름 자체
    r"/api/auth/.*",
]

# 전체를 한 번 더 묶어야 끝의 `/?`가 모든 항목에 걸린다 —
# `|`가 우선순위가 가장 낮아, 묶지 않으면 마지막 항목에만 붙는다.
_PUBLIC = re.compile("(?:" + "|".join(f"(?:{p})" for p in _PUBLIC_PATTERNS) + r")/?$")


def is_public(path: str) -> bool:
    """`/api` 밖(정적 파일·헬스체크)은 이 함수가 판단하지 않는다 — 호출자가 먼저 거른다."""
    return _PUBLIC.fullmatch(path) is not None
