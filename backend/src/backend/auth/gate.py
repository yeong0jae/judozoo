"""공개 API 허용목록.

기본이 차단이다 — 여기 없는 `/api` 경로는 로그인을 요구한다.
엔드포인트마다 의존성을 붙이면 새로 추가할 때 빠뜨리기 쉽고, 빠뜨린 쪽이 열린 채로 남는다.
반대로 해두면 빠뜨렸을 때 막히므로 사고가 나도 노출이 아니라 불편으로 끝난다.

공개로 두는 것은 주도주 후보 **목록**과 모든 화면에 뜨는 헤더용 지수뿐이다.
"""

import re

# 경로 파라미터는 하나의 세그먼트만 받는다.
_SEG = r"[^/]+"

_PUBLIC_PATTERNS = [
    # 주도주 후보 **목록만** 공개한다. 종목 상세(일봉·분봉·수급)는 로그인 뒤다.
    r"/api/leading-stocks/candidates",
    # 휴장 배너
    r"/api/market/calendar/status",
    # 헤더 지수 배지 — 공개 화면에도 뜬다. 어디서나 얻을 수 있는 정보라 가릴 값어치가 없다.
    r"/api/market/kospi",
    r"/api/market/kosdaq",
    # 로그인 흐름 자체
    r"/api/auth/.*",
]

_PUBLIC = re.compile("|".join(f"(?:{p})" for p in _PUBLIC_PATTERNS) + r"/?$")


def is_public(path: str) -> bool:
    """`/api` 밖(정적 파일·헬스체크)은 이 함수가 판단하지 않는다 — 호출자가 먼저 거른다."""
    return _PUBLIC.fullmatch(path) is not None
