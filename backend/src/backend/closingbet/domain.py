"""모의 종가베팅 도메인 — 언제 무엇을 할 수 있고, 얼마에 사고팔며, 누가 이겼나.

외부 의존 없음. 시각은 **KST 벽시계**(naive)로, 개장일은 날짜 집합으로 받는다(027 §설계 결정).

하루는 네 칸이다 — 복기 09:05~15:00 · 베팅 15:00~20:00 · 대기 20:00~08:00 · 결과 08:00~09:05.
한 "판"은 베팅한 거래일로 부르고, 다음 개장일 아침에 판다.
"""

import random
import re
from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from enum import Enum

from backend.leadingstock.domain import DailyCandle, MinuteCandle

# --- 시간대 -------------------------------------------------------------------

BET_OPEN = time(15, 0)
BET_CLOSE = time(20, 0)
RESULT_START = time(8, 0)
RESULT_END = time(9, 5)


class Moment(str, Enum):
    REVIEW = "review"
    BET = "bet"
    NIGHT = "night"
    RESULT = "result"


class OpenDays:
    """개장일 집합. 이 범위 밖의 날짜는 묻지 않는다 — 지어내지 않는다."""

    def __init__(self, days: Iterable[date]) -> None:
        self._days = sorted(set(days))

    def is_open(self, on: date) -> bool:
        return on in self._days

    def previous(self, on: date) -> date | None:
        earlier = [d for d in self._days if d < on]
        return earlier[-1] if earlier else None

    def next(self, on: date) -> date | None:
        later = [d for d in self._days if d > on]
        return later[0] if later else None

    def latest_until(self, on: date) -> date | None:
        """`on` 당일 포함, 그 전 마지막 개장일."""
        return on if self.is_open(on) else self.previous(on)


@dataclass(frozen=True)
class Phase:
    """지금 화면이 다루는 판들.

    - `betting` — 베팅을 받거나(베팅) 체결을 기다리는(대기) 판. 그 밖에는 None
    - `review` — 복기·결과 발표에 보여 줄 판(가장 최근에 팔았거나 지금 파는 판)
    - `next_at` — 다음 칸이 시작하는 시각(카운트다운)
    """

    moment: Moment
    betting: date | None
    review: date | None
    next_at: datetime | None

    @property
    def accepts_bets(self) -> bool:
        return self.moment is Moment.BET


def phase_at(now: datetime, days: OpenDays) -> Phase:
    today = now.date()
    t = now.time()
    previous = days.previous(today)
    if not days.is_open(today) or t < RESULT_START:
        # 직전 개장일 밤에 체결된 판이 아직 안 팔렸다. 쉬는 날도 다음 개장일 아침까지 그 판을 기다린다
        upcoming = today if days.is_open(today) else days.next(today)
        return Phase(
            Moment.NIGHT,
            betting=previous,
            review=days.previous(previous) if previous else None,
            next_at=datetime.combine(upcoming, RESULT_START) if upcoming else None,
        )
    if t < RESULT_END:
        return Phase(Moment.RESULT, betting=None, review=previous, next_at=datetime.combine(today, RESULT_END))
    if t < BET_OPEN:
        return Phase(Moment.REVIEW, betting=None, review=previous, next_at=datetime.combine(today, BET_OPEN))
    if t < BET_CLOSE:
        return Phase(Moment.BET, betting=today, review=previous, next_at=datetime.combine(today, BET_CLOSE))
    upcoming = days.next(today)
    return Phase(Moment.NIGHT, betting=today, review=previous, next_at=datetime.combine(upcoming, RESULT_START) if upcoming else None)


# --- 베팅 ---------------------------------------------------------------------

MIN_AMOUNT_MAN = 100  # 100만원
MAX_AMOUNT_MAN = 10_000  # 1억
AMOUNT_STEP_MAN = 100


class BetRejected(ValueError):
    """베팅을 받지 않는 이유. 메시지는 화면에 그대로 보여 줄 문장이다."""


def check_amount(amount_man: int) -> None:
    if not MIN_AMOUNT_MAN <= amount_man <= MAX_AMOUNT_MAN or amount_man % AMOUNT_STEP_MAN:
        raise BetRejected("100만원부터 1억까지 100만원 단위로 걸 수 있어요")


def shares_for(amount_man: int, price: int) -> int:
    """⌊금액 ÷ 가격⌋. 한 주도 못 사면 베팅할 수 없다."""
    shares = amount_man * 10_000 // price
    if shares < 1:
        raise BetRejected("이 금액으로는 한 주도 살 수 없어요")
    return shares


def check_bet(phase: Phase, amount_man: int, stock_code: str, pool: Iterable[str], price: int) -> int:
    """베팅·변경 검증. 통과하면 지금 가격 기준 예상 주식 수를 준다(확정은 20:00 종가)."""
    if not phase.accepts_bets:
        raise BetRejected("베팅은 15:00부터 20:00까지예요")
    check_amount(amount_man)
    if stock_code not in set(pool):
        raise BetRejected("지금 주도주·후보 목록에 있는 종목만 고를 수 있어요")
    return shares_for(amount_man, price)


# --- 매도 ---------------------------------------------------------------------

SELL_WINDOW = timedelta(minutes=5)
NXT_SELL_START = time(8, 0)
KRX_SELL_START = time(9, 0)


def sell_start(nxt_listed: bool) -> time:
    return NXT_SELL_START if nxt_listed else KRX_SELL_START


def reveal_at(sell_day: date, nxt_listed: bool) -> datetime:
    """결과를 공개하는 시각 — 매도 창이 끝나는 순간."""
    return datetime.combine(sell_day, sell_start(nxt_listed)) + SELL_WINDOW


@dataclass(frozen=True)
class SellPrice:
    price: float
    #: 실제로 값을 낸 5분의 시작 — 창에 체결이 없으면 미뤄진 시각
    window_start: datetime | None


def sell_price(bars: Sequence[MinuteCandle], sell_day: date, nxt_listed: bool, buy_price: int) -> SellPrice:
    """다음 날 아침 5분봉의 (고가 + 저가) ÷ 2.

    창에 체결이 없으면 그날 첫 체결부터 5분으로 미룬다. 그날 끝까지 없으면(거래정지) 매수가 — 0%.
    분봉 시각은 그 1분의 시작이다(08:00 봉 = 08:00~08:01).
    """
    start = datetime.combine(sell_day, sell_start(nxt_listed))
    traded = sorted((b for b in bars if b.date_time >= start and b.date_time.date() == sell_day and b.volume > 0), key=lambda b: b.date_time)
    if not traded:
        return SellPrice(float(buy_price), None)
    first = traded[0].date_time
    window_start = start if first < start + SELL_WINDOW else first
    window = [b for b in traded if window_start <= b.date_time < window_start + SELL_WINDOW]
    return SellPrice((max(b.high_price for b in window) + min(b.low_price for b in window)) / 2, window_start)


@dataclass(frozen=True)
class Outcome:
    pnl: int  # 원
    rate: float  # %

    @staticmethod
    def of(shares: int, buy_price: int, sell: float) -> "Outcome":
        """수수료·세금은 빼지 않는다. 0.5원 단위 매도가 때문에 수익금은 원 단위로 반올림."""
        return Outcome(pnl=round(shares * (sell - buy_price)), rate=(sell / buy_price - 1) * 100)


# --- 종베 체크 · 등급 -----------------------------------------------------------

NEAR_HIGH_RATE = -1.5  # 고가 대비 %
#: 최근 고점 — 주도주 조건 "최근 고가 대비 현재가"(`leadingstock.filters.DailyHighPositionFilter`)와 같은 60봉
RECENT_HIGH_DAYS = 60


def recent_high_gap(history: Sequence[DailyCandle], price: int) -> float | None:
    """최근 고점과의 거리(%) — 직전 60거래일 최고가 대비. `history`는 **오늘을 뺀** 과거 일봉.

    종목 상세의 주도주 조건과 같은 식이다. 두 화면이 같은 종목에 다른 말을 하면 안 된다.
    """
    past = sorted(history, key=lambda c: c.date, reverse=True)[:RECENT_HIGH_DAYS]
    if not past:
        return None
    top = max(c.high_price for c in past)
    if top <= 0:
        return None
    return (price - top) / top * 100


class Grade(str, Enum):
    S = "S"
    A = "A"
    B = "B"
    C = "C"


REGULAR_CLOSE = time(15, 30)


def regular_close(bars: Sequence[MinuteCandle], day: date) -> int | None:
    """그날 정규장 종가 — 15:30 단일가까지의 마지막 체결. 정규장이 아직 안 끝났으면 None.

    15:30 뒤에도 20:00까지 체결이 이어져(027 0단계) 일봉 종가는 정규장 종가가 아니다. 분봉으로 따로 잡는다.
    """
    today = sorted((b for b in bars if b.date_time.date() == day and b.volume > 0), key=lambda b: b.date_time)
    if not any(b.date_time.time() >= REGULAR_CLOSE for b in today):
        return None
    regular = [b for b in today if b.date_time.time() <= REGULAR_CLOSE]
    return regular[-1].close_price if regular else None


@dataclass(frozen=True)
class Checks:
    """종베 체크 여섯 가지. 순매수는 통합(KRX+NXT), 마감 부근 수급은 그 종목 시장의 마감 + 애프터 외인 + 기관,
    구간 신고가는 직전 60거래일 최고가에서 기준(−5%) 안, 애프터 버팀은 20:00 종가가 15:30 정규장 종가 이상."""

    near_high: bool
    foreign: bool
    institution: bool
    market_late: bool
    recent_high: bool
    after_hold: bool

    @staticmethod
    def of(
        price: int, high: int, foreign_net: int, institution_net: int, market_late_net: int,
        recent_high_gap: float | None, recent_high_floor: float, regular_close: int | None,
    ) -> "Checks":
        """`recent_high_floor`는 주도주 조건의 기준(`max_high_position_drop_rate`, −5%)을 그대로 받는다."""
        return Checks(
            # 나눗셈 대신 곱셈 — 정확히 −1.5%인 경계가 부동소수 오차로 떨어지지 않게
            near_high=high > 0 and price * 100 >= high * (100 + NEAR_HIGH_RATE),
            foreign=foreign_net > 0,
            institution=institution_net > 0,
            market_late=market_late_net > 0,
            recent_high=recent_high_gap is not None and recent_high_gap >= recent_high_floor,
            # 정규장이 아직 안 끝났으면(15:00~15:30) 모른다 — 통과로 세지 않는다
            after_hold=regular_close is not None and price >= regular_close,
        )

    @property
    def passed(self) -> int:
        return sum((self.near_high, self.foreign, self.institution, self.market_late, self.recent_high, self.after_hold))

    @property
    def grade(self) -> Grade:
        n = self.passed
        return Grade.S if n == 6 else Grade.A if n == 5 else Grade.B if n == 4 else Grade.C


# --- 판돈 ---------------------------------------------------------------------


@dataclass(frozen=True)
class Stake:
    """한 사람의 걸어 둔 베팅."""

    user_id: int
    stock_code: str
    amount_man: int
    placed_at: datetime


class Stakes:
    def __init__(self, stakes: Iterable[Stake]) -> None:
        self._stakes = list(stakes)

    @property
    def total_man(self) -> int:
        return sum(s.amount_man for s in self._stakes)

    @property
    def players(self) -> int:
        return len({s.user_id for s in self._stakes})

    def by_stock(self) -> dict[str, int]:
        pots: dict[str, int] = {}
        for s in self._stakes:
            pots[s.stock_code] = pots.get(s.stock_code, 0) + s.amount_man
        return pots

    def crowd(self) -> dict[str, int]:
        counts: dict[str, int] = {}
        for s in self._stakes:
            counts[s.stock_code] = counts.get(s.stock_code, 0) + 1
        return counts


# --- 랭킹 ---------------------------------------------------------------------


@dataclass(frozen=True)
class Result:
    user_id: int
    stock_code: str
    placed_at: datetime
    outcome: Outcome


@dataclass(frozen=True)
class Ranked:
    rank: int
    result: Result


class Results:
    """한 판의 정산된 베팅들. 순위는 공동 순위(1, 1, 3), 같은 값끼리는 먼저 건 사람이 위."""

    def __init__(self, results: Iterable[Result]) -> None:
        self._results = list(results)

    def ranked(self, by_rate: bool) -> list[Ranked]:
        def value(r: Result) -> float:
            return r.outcome.rate if by_rate else r.outcome.pnl

        ordered = sorted(self._results, key=lambda r: (-value(r), r.placed_at))
        ranked: list[Ranked] = []
        for i, r in enumerate(ordered):
            same = ranked and value(ranked[-1].result) == value(r)
            ranked.append(Ranked(ranked[-1].rank if same else i + 1, r))
        return ranked

    def top(self, by_rate: bool, n: int = 5) -> list[Ranked]:
        return self.ranked(by_rate)[:n]

    def rank_of(self, user_id: int, by_rate: bool) -> Ranked | None:
        return next((x for x in self.ranked(by_rate) if x.result.user_id == user_id), None)

    def __len__(self) -> int:
        return len(self._results)


# --- 닉네임 -------------------------------------------------------------------

NICKNAME_PATTERN = re.compile(r"^[가-힣a-zA-Z0-9]{2,10}$")
RENAME_COOLDOWN = timedelta(days=7)

_ADJECTIVES = ("용감한", "느긋한", "조용한", "단단한", "날쌘", "반짝이는", "푸른", "졸린", "새벽", "번개")
_ANIMALS = ("수달", "판다", "고래", "거북", "치타", "올빼미", "토끼", "돌고래", "여우", "다람쥐")


class NicknameRejected(ValueError):
    """닉네임을 받지 않는 이유 — 화면에 그대로 보여 줄 문장."""


def check_nickname(raw: str, banned: Iterable[str]) -> str:
    nick = raw.strip()
    if not NICKNAME_PATTERN.match(nick):
        raise NicknameRejected("2~10자, 한글·영문·숫자만 쓸 수 있어요")
    lowered = nick.lower()
    if any(word and word.lower() in lowered for word in banned):
        raise NicknameRejected("쓸 수 없는 단어가 들어 있어요")
    return nick


def next_rename_at(renamed_at: datetime | None) -> datetime | None:
    """다음에 바꿀 수 있는 시각. 처음 받은 랜덤 닉네임은 바로 바꿀 수 있다."""
    return renamed_at + RENAME_COOLDOWN if renamed_at else None


def check_rename(renamed_at: datetime | None, now: datetime) -> None:
    at = next_rename_at(renamed_at)
    if at and now < at:
        raise NicknameRejected(f"{at.month}/{at.day}부터 바꿀 수 있어요")


def random_nickname(taken: set[str], rng: random.Random) -> str:
    """형용사 + 동물. 다 찼으면 숫자를 붙인다."""
    combos = [a + b for a in _ADJECTIVES for b in _ANIMALS if len(a + b) <= 10]
    free = [c for c in combos if c not in taken]
    if free:
        return rng.choice(free)
    while True:
        nick = rng.choice(combos)[:8] + str(rng.randint(10, 99))
        if nick not in taken:
            return nick
