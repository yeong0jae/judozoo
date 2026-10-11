"""해외 주도주 도메인.

외부 의존 없음 — 시각·HTTP·설정을 직접 만지지 않고 파라미터로 받는다.
"""

from dataclasses import dataclass, replace
from datetime import datetime, time

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

    @property
    def previous_close(self) -> float:
        """전일 종가 — 대비(diff)는 현재가에서 전일 종가를 뺀 값이다."""
        return self.price - self.diff


#: 주도주 점수에서 거래대금 축에 주는 무게(나머지 0.45는 등락률).
#: **국내(0.65)보다 낮다** — 미국 풀은 거래대금 편차가 훨씬 커서 같은 값이면 크게 오른 종목이 대형주에 묻힌다.
#: 0.58 근처는 피한다 — 대형주 서넛이 촘촘히 몰려 시세가 조금만 움직여도 순위가 뒤집힌다.
TRADING_VALUE_WEIGHT = 0.55


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


#: 미국 정규장 시작(현지 시각). 분봉에는 장전·장후 거래도 섞여 있어 시가는 이 뒤 첫 봉에서 읽는다.
US_REGULAR_OPEN = time(9, 30)


def regular_session_open(bars: list[tuple[datetime, float]]) -> float | None:
    """한 거래일치 (현지 시각, 시가) 중 정규장 첫 봉의 시가. 아직 장전이라 없으면 None."""
    regular = [(at, price) for at, price in bars if at.time() >= US_REGULAR_OPEN and price > 0]
    return min(regular)[1] if regular else None


@dataclass(frozen=True)
class FilterResult:
    filter_name: str
    criteria_description: str
    actual_value: str
    passed: bool
    #: 실측값·기준값을 숫자로 — 화면이 눈금 막대를 그린다. 막대가 없는 조건은 None
    value: float | None = None
    threshold: float | None = None
