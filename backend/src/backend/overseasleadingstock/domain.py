"""해외 주도주 도메인.

외부 의존 없음 — 시각·HTTP·설정을 직접 만지지 않고 파라미터로 받는다.
"""

from dataclasses import dataclass, replace
from datetime import datetime

# 거래대금순위 API엔 ETF 구분 필드가 없어 영문명 키워드로 판별한다(휴리스틱).
# 발행사 브랜드 위주로 잡아 일반기업 오탐을 줄인다 — TRUST·FUND 등 흔한 단어는 일부러 제외.
ETF_KEYWORDS = (
    " ETF", " ETN", "ISHARES", "SPDR", "INVESCO", "PROSHARES", "DIREXION",
    "VANGUARD", "GLOBAL X", "VANECK", "GRANITESHARES", "WISDOMTREE", "FIRST TRUST",
)


@dataclass(frozen=True)
class OverseasStockRank:
    rank: int
    exchange: str
    symbol: str
    name: str
    ename: str
    price: float
    diff: float
    rate: float
    trading_value: float

    @property
    def is_etf(self) -> bool:
        upper = self.ename.upper()
        return any(keyword in upper for keyword in ETF_KEYWORDS)

    def ranked(self, rank: int) -> "OverseasStockRank":
        return replace(self, rank=rank)


@dataclass(frozen=True)
class FilterResult:
    filter_name: str
    criteria_description: str
    actual_value: str
    passed: bool
