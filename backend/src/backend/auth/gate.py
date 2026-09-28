"""공개 API 허용목록.

기본이 차단이다 — 여기 없는 `/api` 경로는 로그인을 요구한다.
엔드포인트마다 의존성을 붙이면 새로 추가할 때 빠뜨리기 쉽고, 빠뜨린 쪽이 열린 채로 남는다.
반대로 해두면 빠뜨렸을 때 막히므로 사고가 나도 노출이 아니라 불편으로 끝난다.

공개로 두는 것은 주도주 후보 **목록**, 종목 시그널·지지저항 **미리보기**,
그리고 지수·선물의 **시세와 차트**다. 투자자 수급(`investor/*`)은 로그인 뒤다.
미리보기처럼 일부만 여는 경로는 엔드포인트가 직접 잘라 내려보낸다.
"""

import re

# 경로 파라미터는 하나의 세그먼트만 받는다.
_SEG = r"[^/]+"

_PUBLIC_PATTERNS = [
    # 주도주 후보 **목록만** 공개한다. 종목 상세(일봉·분봉·수급)는 로그인 뒤다.
    r"/api/leading-stocks/candidates",
    # 첫 화면 주도주 — 후보 목록에서 다섯 줄만 추린 것이라 새로 여는 것이 없다
    r"/api/leading-stocks/leaders",
    r"/api/overseas-leading-stocks/leaders",
    # 주도주 캘린더 — 위 두 카드가 마감 때 고른 것을 날짜별로 모은 것이라 새로 여는 것이 없다
    r"/api/leader-calendar",
    # 주도주 타임라인 — 같은 주도주를 1분마다 모은 것
    r"/api/leader-timeline",
    # 왜 오르나 — 사유 한 줄은 공개. 근거·관련 기사는 엔드포인트가 로그인일 때만 싣는다
    r"/api/insight/reasons",
    # 종목 시그널 — 미리보기로 최신 몇 건만 연다. 자르는 건 엔드포인트가 한다
    # (여기서 열어도 응답 전체가 나가지는 않는다). 지수 시그널은 로그인 뒤다.
    r"/api/leading-stocks/signal-events",
    # 지지·저항도 같은 방식 — 상위 몇 개만 엔드포인트가 잘라 내려보낸다
    r"/api/leading-stocks/breakout-radar",
    r"/api/overseas-leading-stocks/candidates",
    # 휴장 배너
    r"/api/market/calendar/status",
    # 지수 시세 — 어디서나 얻을 수 있는 정보라 가릴 값어치가 없다.
    r"/api/market/kospi",
    r"/api/market/kosdaq",
    r"/api/market/nasdaq/quote",
    r"/api/market/macro/quotes",
    # 시세와 차트는 연다. **수급(`investor/*`)만 로그인 뒤로 남긴다** — 지수·선물 가격은
    # 어디서나 구할 수 있지만, 투자자별 순매수는 이 화면이 가공해 주는 값이다.
    # `[^/]+`는 한 세그먼트라 `/futures/...`나 `/investor/...`로는 번지지 않는다.
    rf"/api/market/{_SEG}/candles",
    rf"/api/market/futures/{_SEG}/quote",
    rf"/api/market/futures/{_SEG}/candles",
    # 로그인 흐름 자체
    r"/api/auth/.*",
]

# 전체를 한 번 더 묶어야 끝의 `/?`가 모든 항목에 걸린다 —
# `|`가 우선순위가 가장 낮아, 묶지 않으면 마지막 항목에만 붙는다.
_PUBLIC = re.compile("(?:" + "|".join(f"(?:{p})" for p in _PUBLIC_PATTERNS) + r")/?$")


def is_public(path: str) -> bool:
    """`/api` 밖(정적 파일·헬스체크)은 이 함수가 판단하지 않는다 — 호출자가 먼저 거른다."""
    return _PUBLIC.fullmatch(path) is not None
