"""주도주 "왜 오르나" — 매분 만들 종목을 골라 Gemini로 사유를 만들고, 화면에는 고른 한 행씩 내준다.

무엇이 주도주인지는 홈 카드가 정한다(`find_leaders`·`get_leaders`). 여기서는 그 5종목에 사유를 붙인다.
Gemini는 두 번 부른다 — 검색 켠 문장 → 서버가 출처를 실제 기사로 → 검색 없는 JSON(번호만)(026 §생성 파이프라인).
"""

import logging
from collections import defaultdict
from dataclasses import dataclass
from datetime import date, datetime, time

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from backend.insight.domain import (
    SCHEDULES,
    Attempts,
    Draft,
    Schedule,
    Sources,
    Trigger,
    Verdict,
    judge,
    pick,
    previous_weekday,
    shown,
)
from backend.insight.entities import StockReason
from backend.leadingstock import application as leadingstock
from backend.library.time import KST
from backend.market import calendar
from backend.market.calendar import Region
from backend.overseasleadingstock import application as overseasleadingstock
from backend.platform.vertex import articles
from backend.platform.vertex import client as vertex
from backend.platform.vertex.client import VertexError
from backend.settings import get_settings

log = logging.getLogger(__name__)

#: 홈 주도주 카드의 줄 수(`leadercalendar.application.LEADERS_COUNT`와 같은 값)
LEADERS_COUNT = 5

_SCHEMA = {
    "type": "OBJECT",
    "properties": {
        "explained": {"type": "BOOLEAN"},
        "keywords": {"type": "ARRAY", "items": {"type": "STRING"}},
        "reason": {"type": "STRING"},
        "evidence": {"type": "ARRAY", "items": {"type": "INTEGER"}},
        "related": {"type": "ARRAY", "items": {"type": "INTEGER"}},
    },
    "required": ["explained", "keywords", "reason", "evidence", "related"],
}


@dataclass(frozen=True)
class Leader:
    code: str
    #: 화면·저장용 이름
    name: str
    #: 검색에 넣는 이름 — 해외는 영문명이 더 잘 찾힌다
    search_name: str
    rate: float
    trading_value: str


def local(region: Region, at_kst: datetime) -> datetime:
    """KST 벽시계 → 그 시장의 현지 벽시계(naive)."""
    return at_kst.replace(tzinfo=KST).astimezone(region.zone).replace(tzinfo=None)


# --- 만들기 ---------------------------------------------------------------


def run(session: Session, region: Region, now_kst: datetime) -> int:
    """지금 만들 종목을 골라 만든다. 만든 행 수를 돌려준다. 휴장일은 부르는 쪽(스케줄러)이 거른다."""
    schedule = SCHEDULES[region]
    now = local(region, now_kst)
    if not schedule.in_window(now.time()):
        return 0

    leaders = {lead.code: lead for lead in _leaders(region)}
    rows = _rows(session, region, now.date())
    history: dict[str, list] = defaultdict(list)
    for r in rows:
        history[r.code].append(r.attempt(local(region, r.generated_at)))
    picks = pick(schedule, list(leaders), {c: Attempts(a) for c, a in history.items()}, now)

    made = 0
    for code, trigger in picks:
        if _used_today(session, now_kst) >= get_settings().vertex.daily_limit:
            log.warning("왜 오르나 — 하루 상한에 닿아 오늘은 멈춘다 (%s개 남김)", len(picks) - made)
            break
        session.add(make(region, schedule, leaders[code], trigger, now.date(), now_kst))
        session.commit()  # 한 종목씩 — 뒤 종목이 실패해도 앞 종목은 남는다
        made += 1
    return made


def make(region: Region, schedule: Schedule, leader: Leader, trigger: Trigger, day: date, now_kst: datetime) -> StockReason:
    """한 종목. Vertex가 실패해도 행은 남긴다 — 3분 재시도가 그 행을 보고 돈다."""
    since = schedule.articles_since(_previous_open_day(region, day))
    try:
        grounded = vertex.ground(_explain_prompt(region, leader, day, since))
        raw = [
            (a.site or s.domain, a.title, a.url)
            for s in grounded.sources
            if (a := articles.resolve(s.uri)) is not None
        ]
        sources = Sources.of(raw)
        data, _ = vertex.structure(_structure_prompt(leader, day, grounded.text, sources), _SCHEMA)
        verdict = judge(Draft.from_json(data), sources, stock_name=leader.name)
    except VertexError as exc:
        verdict = Verdict(published=False, error=str(exc))
    if not verdict.published:
        log.info("왜 오르나 거절 %s %s — %s", region.value, leader.code, verdict.error)
    return StockReason.made(
        region, day, leader.code, leader.name, now_kst, trigger, verdict, get_settings().vertex.model,
    )


def _leaders(region: Region) -> list[Leader]:
    if region == Region.KR:
        return [
            Leader(
                code=s.stock_code.split("_", 1)[0], name=s.stock_name, search_name=s.stock_name,
                rate=s.price_change_rate, trading_value=f"{s.accumulated_trading_value / 1e8:,.0f}억 원",
            )
            for s in leadingstock.find_leaders(LEADERS_COUNT)
        ]
    return [
        Leader(
            code=s.symbol, name=s.name, search_name=s.ename or s.name,
            rate=s.rate, trading_value=f"${s.trading_value / 1e9:,.2f}B",
        )
        for s in overseasleadingstock.get_leaders(LEADERS_COUNT)
    ]


def _previous_open_day(region: Region, day: date) -> date:
    """국내는 개장일 목록으로(연휴를 건너뛴다). 해외는 목록이 없어 직전 평일 — 미국 공휴일 다음 날엔 하루 넓어진다."""
    if region == Region.KR:
        return calendar.previous_open_day(day) or previous_weekday(day)
    return previous_weekday(day)


def _explain_prompt(region: Region, leader: Leader, day: date, since: datetime) -> str:
    market, zone = ("국내", "KST") if region == Region.KR else ("미국", "뉴욕 시각")
    return (
        f"{leader.search_name}({leader.code})이 {day.isoformat()} {market} 증시에서 {leader.rate:+.2f}% 올랐다"
        f"(거래대금 {leader.trading_value}). 오른 이유를 Google 검색으로 찾아 한국어 한 문단으로 설명해라.\n"
        f"- {since:%Y-%m-%d %H:%M} {zone} 이후에 나온 기사만 근거로 삼는다.\n"
        "- 기사로 설명되지 않으면 그렇다고 말한다. 추측으로 이유를 만들지 않는다.\n"
        "- 투자 권유 표현(매수·매도 의견, 목표가, 추천)은 쓰지 않는다."
    )


def _structure_prompt(leader: Leader, day: date, explanation: str, sources: Sources) -> str:
    return (
        f"종목: {leader.name}({leader.code}), {day.isoformat()}, {leader.rate:+.2f}%\n\n"
        f"검색으로 정리한 설명:\n{explanation}\n\n기사 목록:\n{sources.listing()}\n\n"
        "위 설명과 기사 목록만 보고 JSON으로 답해라.\n"
        "- reason: 오른 이유 한 줄. 한국어 30자 안팎. 투자 권유 표현 금지.\n"
        "- keywords: 사유의 핵심 단어 최대 2개. 회사명·제품명·계약 상대 같은 고유명사와 사건을 우선한다. "
        f"종목 자신의 이름({leader.name})과 '상승'·'호재'·'기대'·'강세' 같은 일반어는 쓰지 않는다. 각 8자 안팎.\n"
        "- evidence: 사유를 직접 뒷받침하는 기사 번호 최대 2개. "
        "가격만 전하는 기사나 전망 기사는 근거가 아니다.\n"
        "- related: 사유와 부딪히는 기사나 수급을 다룬 기사 번호 최대 2개.\n"
        "- 목록에 없는 번호는 쓰지 않는다.\n"
        "- 근거 기사가 없으면 explained=false, reason은 빈 문자열, keywords와 evidence는 빈 배열."
    )


def _rows(session: Session, region: Region, day: date) -> list[StockReason]:
    return list(session.scalars(
        select(StockReason).where(StockReason.region == region, StockReason.trading_day == day)
    ).all())


def _used_today(session: Session, now_kst: datetime) -> int:
    """오늘(KST) 국내·해외를 합쳐 만든 행 수 — 하루 상한은 청구서 기준이라 시장을 나누지 않는다."""
    start = datetime.combine(now_kst.date(), time.min)
    return session.scalar(select(func.count()).select_from(StockReason).where(StockReason.generated_at >= start)) or 0


# --- 보여주기 ---------------------------------------------------------------


def session_day(region: Region, now_kst: datetime) -> date:
    """지금 화면이 보여줄 거래일. 프리마켓 시작(국내 08:00·뉴욕 04:00) 전이나 휴장일이면 직전 거래일."""
    now = local(region, now_kst)
    today_open = not calendar.is_holiday(region)
    return SCHEDULES[region].session_day(now, today_open, _previous_open_day(region, now.date()))


def reasons(session: Session, region: Region, now_kst: datetime) -> list[StockReason]:
    """지금 세션의 종목별 사유 — 종목마다 `shown`이 고른 한 행."""
    by_code: dict[str, list[StockReason]] = defaultdict(list)
    for r in _rows(session, region, session_day(region, now_kst)):
        by_code[r.code].append(r)
    return [row for rows in by_code.values() if (row := shown(rows)) is not None]
