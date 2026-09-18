"""해외 주도주 도메인.

외부 의존 없음 — 시각·HTTP·설정을 직접 만지지 않고 파라미터로 받는다.
"""

from dataclasses import dataclass, replace
from datetime import datetime

from backend.library.ranking import top_balanced

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


#: 국내 `leadingstock.domain.TRADING_VALUE_WEIGHT`와 같은 값을 쓴다 — 고른 이유도 거기에.
TRADING_VALUE_WEIGHT = 0.65


class OverseasStockRanks:
    """거래대금 상위 풀 — 그 안에서 "주도주다움"으로 다시 세운다.

    국내 `LeadingStocks`와 같은 규칙이다. 두 피처가 같은 계산을 공유하되 서로를
    부르지는 않는다 — 규칙은 `library.ranking`이 갖고, 무엇이 두 축인지만 각자 정한다.
    """

    def __init__(self, stocks: list[OverseasStockRank]) -> None:
        self._stocks = stocks

    def leaders(self, count: int) -> list[OverseasStockRank]:
        """거래대금·등락률 두 축이 모두 높은 순으로 `count`개. 오른 종목만 본다."""
        risen = [s for s in self._stocks if s.rate > 0]
        return top_balanced(
            risen, lambda s: s.trading_value, lambda s: s.rate, count, TRADING_VALUE_WEIGHT
        )


@dataclass(frozen=True)
class FilterResult:
    filter_name: str
    criteria_description: str
    actual_value: str
    passed: bool
