"""주도주 "왜 오르나" 도메인 — 언제 만들고, Gemini 답의 무엇을 믿고, 무엇을 보여주는가.

외부 의존 없음. 시각은 **시장 현지 벽시계**(naive)로 받는다 — 국내는 KST, 해외는 뉴욕.
해외 시각표를 뉴욕 기준으로 두면 서머타임이 바뀌어도 미국 장의 같은 순간에 돈다(026 §설계 결정).
"""

import html
import re
from collections.abc import Iterable, Iterator, Sequence
from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta
from enum import Enum
from typing import Protocol, TypeVar

from backend.market.calendar import Region

# --- 시각표 ---------------------------------------------------------------


@dataclass(frozen=True)
class Schedule:
    """한 시장의 생성 규칙. 모든 시각은 그 시장의 현지 시각이다."""

    #: 이 시각부터 오늘을 보여준다(프리마켓 시작) — 그 전에는 직전 거래일
    reset: time
    #: 새로 들어온 종목을 바로 만드는 구간(양 끝 포함)
    window: tuple[time, time]
    #: 그 시각의 주도주를 다시 만드는 시각들
    slots: tuple[time, ...]
    #: 정규장 마감 — 기사 범위의 시작("직전 거래일 장 마감 이후")
    close: time

    def in_window(self, t: time) -> bool:
        return self.window[0] <= _minute(t) <= self.window[1]

    def is_slot(self, t: time) -> bool:
        """정해진 시각부터 5분 동안. 한 번 실행이 1분을 넘기면 스케줄러가 겹친 실행을 건너뛰어
        정각을 놓친다 — 폭을 두고, 겹쳐 만드는 건 30분 규칙(`RECENT`)이 막는다."""
        m = datetime.combine(date.min, _minute(t))
        return any(timedelta(0) <= m - datetime.combine(date.min, s) < SLOT_GRACE for s in self.slots)

    def articles_since(self, previous_open_day: date) -> datetime:
        """기사 범위의 시작 — 직전 거래일 정규장 마감. 그보다 오래된 기사는 오늘 사유로 보기 어렵다."""
        return datetime.combine(previous_open_day, self.close)

    def session_day(self, local_now: datetime, today_open: bool, previous_open_day: date) -> date:
        """지금 보여줄 거래일. 초기화 작업 없이 조회 조건만으로 "프리마켓 시작에 초기화"가 된다."""
        if not today_open or local_now.time() < self.reset:
            return previous_open_day
        return local_now.date()


def _minute(t: time) -> time:
    return time(t.hour, t.minute)


SLOT_GRACE = timedelta(minutes=5)


def previous_weekday(on: date) -> date:
    """`on` 직전 평일. 휴장일 목록이 없을 때(해외, 국내 조회 실패)의 직전 거래일 대용."""
    day = on - timedelta(days=1)
    while day.weekday() >= 5:
        day -= timedelta(days=1)
    return day


SCHEDULES: dict[Region, Schedule] = {
    Region.KR: Schedule(
        reset=time(8, 0),
        window=(time(8, 30), time(20, 0)),
        slots=(time(8, 30), time(9, 30), time(11, 0), time(14, 0), time(16, 0), time(18, 0), time(20, 0)),
        close=time(15, 30),
    ),
    # KST(서머타임 중) 21:00 · 22:00 · 23:00 · 00:00 · 02:00 · 05:30 · 07:00
    Region.US: Schedule(
        reset=time(4, 0),
        window=(time(8, 0), time(18, 0)),
        slots=(time(8, 0), time(9, 0), time(10, 0), time(11, 0), time(13, 0), time(16, 30), time(18, 0)),
        close=time(16, 0),
    ),
}

# --- 고르기 ---------------------------------------------------------------

#: 정해진 시각에 이만큼 안에 만든 종목은 건너뛴다 — 09:28 진입 → 09:29 생성 → 09:30 또 생성을 막는다
RECENT = timedelta(minutes=30)
#: Vertex가 아파 실패한 종목을 다시 만들기까지 기다리는 시간. 이어진 실패가 n번이면 n번째 값을 쓴다 —
#: 세 번 다 실패하면 정해진 시각에 맡긴다. 한 번만 다시 하면 몇 분 이어지는 장애에 그대로 진다
RETRY_AFTER = (timedelta(minutes=1), timedelta(minutes=5), timedelta(minutes=15))


class Trigger(str, Enum):
    ENTRY = "entry"
    SCHEDULED = "scheduled"
    RETRY = "retry"


class Failure(str, Enum):
    """게시하지 못한 까닭. 다시 할지, 서킷이 셀지가 여기서 갈린다(026 §실패 처리)."""

    #: 시간 안에 답이 없다
    TIMEOUT = "timeout"
    #: 429·5xx — 같은 호출 안에서 다시 물어도 안 풀렸다
    UNAVAILABLE = "unavailable"
    #: 400·403·404 등 — 우리 설정이 틀렸다. 다시 해도 같다
    CLIENT_ERROR = "client_error"
    #: 답은 왔는데 쓸 수 없다 — 깨진 JSON, 목록에 없는 기사 번호
    BAD_ANSWER = "bad_answer"

    @property
    def transient(self) -> bool:
        """Vertex가 잠시 아픈 것 — 시간이 지나면 풀린다. 종목을 다시 만들고, 서킷이 센다."""
        return self in (Failure.TIMEOUT, Failure.UNAVAILABLE)


@dataclass(frozen=True)
class Attempt:
    """한 번 만든 기록 — 언제, 게시됐는가, 설명됐는가, 왜 실패했는가만 판단에 쓴다."""

    at: datetime
    published: bool
    explained: bool = False
    failure: Failure | None = None


@dataclass(frozen=True)
class Attempts:
    """한 종목의 오늘 생성 기록."""

    items: Sequence[Attempt] = ()

    @property
    def last(self) -> Attempt | None:
        return max(self.items, key=lambda a: a.at) if self.items else None

    def made_within(self, now: datetime, span: timedelta) -> bool:
        last = self.last
        return last is not None and now - last.at < span

    @property
    def explained(self) -> bool:
        """게시된 사유 중 하나라도 설명됐는가 — 그러면 화면엔 그 사유가 남는다(`shown`)."""
        return any(a.published and a.explained for a in self.items)

    def needs_retry(self, now: datetime) -> bool:
        """끝에서부터 이어진 일시 장애 실패가 n번이고, 마지막 실패 뒤 `RETRY_AFTER[n-1]`이 지났으면."""
        streak = 0
        for a in sorted(self.items, key=lambda a: a.at, reverse=True):
            if a.failure is None or not a.failure.transient:
                break
            streak += 1
        if not 0 < streak <= len(RETRY_AFTER):
            return False
        return now - self.last.at >= RETRY_AFTER[streak - 1]


def pick(
    schedule: Schedule,
    leaders: Sequence[str],
    history: dict[str, Attempts],
    now: datetime,
    candidates: Sequence[str] = (),
) -> list[tuple[str, Trigger]]:
    """지금 만들 종목과 그 이유. 대상은 **지금 주도주**와 **등락률 기준을 넘은 후보**다 —
    빠진 종목은 사유만 남고 다시 만들지 않는다.

    후보는 새로 들어올 때와 실패 재시도, 그리고 **아직 설명 없음일 때만** 정해진 시각에 다시 만든다 —
    사유가 있는 후보까지 매번 다시 만들면 검색이 무료 한도를 넘는다(026 §설계 결정). 급등 직후엔 기사가
    없는 경우가 많아 설명 없음은 한 번 더 본다. 주도주로 올라오면 그때부터 매번 다시 만든다.
    """
    if not schedule.in_window(now.time()):
        return []
    slot = schedule.is_slot(now.time())
    lead = set(leaders)
    picked: list[tuple[str, Trigger]] = []
    for code in [*leaders, *(c for c in candidates if c not in lead)]:
        done = history.get(code, Attempts())
        if not done.items:
            picked.append((code, Trigger.ENTRY))
        elif done.needs_retry(now):
            picked.append((code, Trigger.RETRY))
        elif slot and not done.made_within(now, RECENT) and (code in lead or not done.explained):
            picked.append((code, Trigger.SCHEDULED))
    return picked


# --- 서킷 -----------------------------------------------------------------

#: 일시 장애가 이만큼 이어지면 Vertex를 쉰다
CIRCUIT_THRESHOLD = 3
#: 쉬는 시간. 지나면 한 종목으로 시험한다
CIRCUIT_COOLDOWN = timedelta(minutes=5)


class CircuitState(Enum):
    CLOSED = "closed"
    #: Vertex를 부르지 않는다. 고른 종목은 행 없이 건너뛴다 — 닫힌 뒤 다시 골라진다
    OPEN = "open"
    #: 한 종목만 시험한다
    HALF_OPEN = "half_open"


@dataclass
class Circuit:
    """Vertex가 아플 때 종목마다 타임아웃까지 매달리지 않게 한다. 시각은 부르는 쪽이 준다.

    일시 장애(`Failure.transient`)만 센다. 답이 이상한 건 Vertex가 답한 것이라 성공으로 본다.
    클라이언트 오류는 다시 해도 같으니 사람이 고쳐 재배포할 때까지(새 프로세스가 뜰 때까지) 연다.
    """

    failures: int = 0
    open_until: datetime | None = None
    stuck: bool = False

    def state(self, now: datetime) -> CircuitState:
        if self.stuck:
            return CircuitState.OPEN
        if self.open_until is None:
            return CircuitState.CLOSED
        return CircuitState.OPEN if now < self.open_until else CircuitState.HALF_OPEN

    def record(self, failure: Failure | None, now: datetime) -> None:
        if failure is Failure.CLIENT_ERROR:
            self.stuck = True
            return
        if failure is None or not failure.transient:
            self.failures = 0
            self.open_until = None
            return
        trial = self.state(now) is CircuitState.HALF_OPEN
        self.failures += 1
        if trial or self.failures >= CIRCUIT_THRESHOLD:
            self.open_until = now + CIRCUIT_COOLDOWN
            self.failures = 0


# --- 출처 -----------------------------------------------------------------

#: 페이지 제목 뒤에 붙는 사이트 경로("< 증시 < 기사본문")
_BREADCRUMB = re.compile(r"\s+<\s+.*$")
#: 뒤에 붙는 매체명(" - 파이낸셜뉴스", " | 한국경제"). 20자 넘는 꼬리는 제목의 일부로 본다
_OUTLET = re.compile(r"\s+[-|]\s+[^-|]{1,20}$")

#: 이보다 짧은 제목은 기사가 아니라 사이트 이름이다("알파스퀘어")
MIN_TITLE_LEN = 10

#: 시세 중계 기사 — 오른 이유를 말하지 않고 가격만 전한다
_PRICE_NOISE = re.compile(
    "|".join([
        r"주가,\s*\d{1,2}월\s*\d{1,2}일",
        r"장중\s*[\d,]+원\s*[\d.]+%\s*(상승|하락)",
        r"VI\s*발동",
        r"주가\s*(상승|하락)\s*중$",
        r"\b52-Week (High|Low)\b",
        r"\bHits? (a )?(New )?Record High\b",
        r"\b(Opened|Moved) (Up|Down) by\b",
        r"\bStock Price (Up|Down) [\d.]+%",
        r"\bTime to Buy\b",
        r"\bPrediction:",
        r"\bgains? [\d.]+ percent\b",
    ]),
    re.IGNORECASE,
)


def clean_title(raw: str) -> str:
    title = html.unescape(re.sub(r"\s+", " ", raw)).strip()
    title = _BREADCRUMB.sub("", title)
    return _OUTLET.sub("", title).strip()


def _outlet(label: str) -> str:
    """포털을 거친 기사는 매체명이 "Daum | 서울경제"로 온다 — 원래 매체만 남긴다."""
    return html.unescape(label).split(" | ")[-1].strip()


@dataclass(frozen=True)
class Source:
    id: int
    domain: str
    title: str
    url: str


@dataclass(frozen=True)
class Sources:
    """Gemini에 번호로 보여줄 기사 목록. 모델은 URL을 쓰지 않고 이 번호만 고른다 — 주소를 지어낼 틈이 없다."""

    items: Sequence[Source] = ()

    @classmethod
    def of(cls, raw: Iterable[tuple[str, str, str]]) -> "Sources":
        """(domain, 제목, 실제 URL)들을 정리하고 시세 기사를 뺀 뒤 1부터 번호를 붙인다."""
        kept: list[Source] = []
        for domain, raw_title, url in raw:
            title = clean_title(raw_title)
            if len(title) < MIN_TITLE_LEN or _PRICE_NOISE.search(title) or any(s.url == url for s in kept):
                continue
            kept.append(Source(len(kept) + 1, _outlet(domain), title, url))
        return cls(kept)

    def __iter__(self) -> Iterator[Source]:
        return iter(self.items)

    def __len__(self) -> int:
        return len(self.items)

    def get(self, id_: int) -> Source | None:
        return next((s for s in self.items if s.id == id_), None)

    def listing(self) -> str:
        return "\n".join(f"[{s.id}] {s.domain} | {s.title}" for s in self.items) or "(기사 없음)"


# --- 답 검사 ---------------------------------------------------------------

MAX_ARTICLES = 2
MAX_KEYWORDS = 2
#: 칩 하나 8자 안팎 — 이보다 길면 문장이지 키워드가 아니다
MAX_KEYWORD_LEN = 12

#: 어느 종목에나 붙는 말 — 칩으로 쓰면 아무것도 말하지 않는다
_GENERIC_KEYWORDS = frozenset({
    "상승", "급등", "강세", "호재", "기대", "기대감", "주가", "반등", "특징주", "매수세", "신고가", "수급",
    "rally", "surge", "stock", "shares",
})
#: 직접적인 투자 권유. 목표가·투자의견 변경과 "매수세"·"외국인이 매수하며"·"팔아치운" 같은 사실 전달은 막지 않는다
_ADVICE = re.compile(
    r"추천|매[수도]\s*(?:하(?![며는고])|할|해(?!서))|담아(?![내낸])|사야|팔아(?!치)|\bbuy\b|\bsell\b", re.IGNORECASE,
)


@dataclass(frozen=True)
class Draft:
    """2차 호출이 돌려준 답 그대로."""

    explained: bool
    keywords: list[str]
    reason: str
    evidence: list[int]
    related: list[int]

    @classmethod
    def from_json(cls, d: dict) -> "Draft":
        return cls(
            explained=bool(d.get("explained")),
            keywords=[str(k) for k in d.get("keywords") or []],
            reason=str(d.get("reason") or ""),
            evidence=[int(i) for i in d.get("evidence") or []],
            related=[int(i) for i in d.get("related") or []],
        )


@dataclass(frozen=True)
class Verdict:
    published: bool
    explained: bool = False
    keywords: list[str] = field(default_factory=list)
    reason: str = ""
    evidence: list[Source] = field(default_factory=list)
    related: list[Source] = field(default_factory=list)
    error: str | None = None
    #: 게시하지 못했으면 그 까닭
    failure: Failure | None = None


def judge(draft: Draft, sources: Sources, stock_name: str = "") -> Verdict:
    """게시할지, 무엇을 게시할지. 거절은 행으로 남되 화면에는 이전 사유가 그대로 보인다."""
    unknown = [i for i in [*draft.evidence, *draft.related] if sources.get(i) is None]
    if unknown:
        return Verdict(published=False, error=f"목록에 없는 기사 번호 {unknown}", failure=Failure.BAD_ANSWER)

    evidence = _unique(draft.evidence)[:MAX_ARTICLES]
    related = [i for i in _unique(draft.related) if i not in evidence][:MAX_ARTICLES]
    to_sources = lambda ids: [s for i in ids if (s := sources.get(i)) is not None]  # noqa: E731

    # 목록 한 줄은 명사형으로 끊는다("~ 매수세 유입"). 끝 마침표는 코드로 떼고, "~다" 말투는 프롬프트가 막는다
    reason = draft.reason.strip().rstrip(".。").strip()
    # 근거 없는 사유는 믿지 않는다 — 모델은 설명 없음을 잘 쓰지 않는다(026 §알려진 함정).
    # 권유 표현도 설명 없음으로 내린다 — 근거 기사가 권하는 말투면 다시 만들어도 같은 답이 나오기 쉽다
    advice = _ADVICE.search(" ".join([draft.reason, *draft.keywords])) is not None
    if not draft.explained or not evidence or not reason or advice:
        return Verdict(
            published=True, explained=False, related=to_sources(related), error="권유 표현" if advice else None,
        )
    return Verdict(
        published=True, explained=True, keywords=_keywords(draft.keywords, stock_name), reason=reason,
        evidence=to_sources(evidence), related=to_sources(related),
    )


def _keywords(raw: Sequence[str], stock_name: str) -> list[str]:
    """종목 자신의 이름은 칩이 아니다 — 목록 줄에 이미 적혀 있다."""
    kept: list[str] = []
    for k in (w.strip() for w in raw):
        if (
            k and len(k) <= MAX_KEYWORD_LEN and k.lower() not in _GENERIC_KEYWORDS
            and k != stock_name and k not in kept
        ):
            kept.append(k)
    return kept[:MAX_KEYWORDS]


def _unique(ids: Sequence[int]) -> list[int]:
    return list(dict.fromkeys(ids))


# --- 보여줄 사유 ---------------------------------------------------------------


class _Shown(Protocol):
    generated_at: datetime
    published: bool
    explained: bool


R = TypeVar("R", bound=_Shown)


def shown(rows: Sequence[R]) -> R | None:
    """가장 최근의 게시·설명된 행. 없을 때만 가장 최근의 게시된 설명 없음 —
    검색 결과는 매번 달라서, 나중에 나온 설명 없음이 멀쩡하던 사유를 지우면 안 된다."""
    published = sorted((r for r in rows if r.published), key=lambda r: r.generated_at)
    explained = [r for r in published if r.explained]
    if explained:
        return explained[-1]
    return published[-1] if published else None
