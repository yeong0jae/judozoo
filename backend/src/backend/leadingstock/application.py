"""주도주 후보 발굴·평가.

Phase 1(거래대금 순위 + 당일 등락률)로 후보를 추리고, 상세 보기에서 전체 필터를 평가한다.
분봉은 차트·돌파선·시그널이 **같은 캐시를 공유**하도록 한 경로로 모은다 — 따로 부르면
키움 rate limit에 걸린다.
"""

import dataclasses
import logging
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta

from backend.leadingstock import filters as flt
from backend.leadingstock import intraday, minute_archive, wide_limit_days
from backend.leadingstock.domain import (
    DailyCandle,
    DailyCandles,
    LeadingStocks,
    LeadingStockSnapshot,
    MinuteCandle,
    MinuteCandles,
    SpikeDirection,
)
from backend.library.cache import ttl_cache
from backend.library.time import now, today
from backend.market import calendar
from backend.platform.kiwoom import market as kiwoom_market
from backend.platform.kiwoom import program as kiwoom_program
from backend.platform.toss import candles as toss_candles
from backend.settings import get_settings
from backend.stock import application as stock_app
from backend.stock.domain import Market

log = logging.getLogger(__name__)

_RVOL_LOOKBACK_DAYS = 20
_SPIKE_BASELINE_BARS = 20             # 직전 평균 산정 봉 수
_SPIKE_MIN_TRADING_VALUE = 1_000_000_000  # 최신 1분봉 최소 거래대금(원)
_CHART_SESSION_DAYS = 3               # 상세 차트 표시 거래일 수(당일 포함)


@dataclass(frozen=True)
class CandidateSignalReading:
    """시그널 전이 판정용 후보 한 종목의 측정값 + 적재 컨텍스트."""

    stock_code: str
    stock_name: str
    current_price: int
    price_change_rate: float
    trading_value: int
    gap_rate: float | None
    peak_price: int | None
    spike_ratio: float | None
    minute_trading_value: int | None
    spike_direction: SpikeDirection | None


@dataclass(frozen=True)
class BreakoutRadarStock:
    """저항·지지 한 종목 — 최근 3거래일 고가(저항선)·저가(지지선) 대비 현재가 갭.

    저항은 위로 남은 거리, 지지는 아래로 남은 거리다. 정렬은 저항 근접 순.
    """

    stock_code: str
    stock_name: str
    current_price: int
    price_change_rate: float
    peak_price: int
    peak_at: datetime
    gap_rate: float
    trading_value: int
    trough_price: int | None = None
    trough_at: datetime | None = None
    support_gap_rate: float | None = None


@dataclass(frozen=True)
class StockEvaluation:
    """종목 상세 평가 — 필터 평가 + 상대거래량 + 소속 시장. 데이터 없으면 각각 None."""

    stock: LeadingStockSnapshot
    filter_results: list[flt.FilterEvaluationResult]
    relative_volume: float | None
    market: Market | None = None


def _criteria():
    return get_settings().criteria


#: 후보 풀의 원천 — 거래대금 상위 몇 개를 받는가.
_POOL_SIZE = 50
#: 장중 수명. 갱신 폴러(15초)가 한 번 늦어도 비지 않게 여유를 둔다.
_POOL_TTL_SECONDS = 20
#: 장이 다시 도는 시각 — `market.calendar`의 거래 시간 시작(NXT 프리마켓)과 같다.
_SESSION_START = time(8, 0)


def _pool_ttl() -> float:
    """장중엔 짧게, 장이 멈춘 동안(장 밖·휴장)은 다음 08:00까지 들고 있는다.

    멈춘 동안엔 값이 안 바뀌니 다시 물을 이유가 없다. 장중에 넣은 값은 20초로 끝나므로
    마감 직전 값이 밤새 남지 않고, 마감 뒤 첫 요청이 받은 값이 다음 장까지 간다.
    휴장일은 08:00에 한 번 더 받아 다음 08:00까지 간다 — 며칠 뒤가 휴장인지 따지지 않는다.
    """
    holiday, trading_hours = calendar.market_status()
    if not holiday and trading_hours:
        return _POOL_TTL_SECONDS
    return _seconds_until_session()


def _seconds_until_session() -> float:
    """다음 08:00까지 남은 초. 장이 멈춘 동안 받은 값을 들고 있을 기한이다."""
    at = now()
    reopen = datetime.combine(at.date(), _SESSION_START)
    if at >= reopen:
        reopen += timedelta(days=1)
    return (reopen - at).total_seconds()


@ttl_cache("tradingValuePool", ttl_seconds=_pool_ttl, maxsize=1)
def _trading_value_pool() -> list[LeadingStockSnapshot]:
    """거래대금 상위 — 후보 목록·주도주·상세 평가가 같은 응답을 나눠 쓴다.

    오늘 +30%를 넘은 적이 있는 종목(상장 첫날)에는 여기서 표시를 붙인다 — 순위가 그 표시를 본다.
    """
    return wide_limit_days.mark(kiwoom_market.fetch_top_trading_value_stocks(_POOL_SIZE), _pool_trade_date(), now())


def _pool_trade_date() -> date:
    """받은 순위가 어느 거래일 값인가 — 장이 열리기 전(08시 전)·휴장일엔 직전 거래일이다.

    오늘 날짜를 붙이면 자정 넘어 받은 상장 첫날 값이 다음 거래일 내내 상장 첫날로 남는다.
    거래일 목록을 못 받았으면 오늘로 둔다 — 날짜를 지어내지 않는다.
    """
    holiday, _ = calendar.market_status()
    at = now()
    if not holiday and at.time() >= _SESSION_START:
        return at.date()
    return calendar.previous_open_day(at.date()) or at.date()


def refresh_trading_value_pool() -> None:
    """거래대금 상위를 만료 전에 새로 받아 갈아 끼운다 — 장중 갱신 폴러가 부른다."""
    _trading_value_pool.refresh()


def sync_today_minutes() -> int:
    """감시 풀(시그널·돌파와 같은 풀)의 당일 분봉을 이어 받는다. 실패한 종목 수를 돌려준다."""
    pool = find_candidate_stocks(get_settings().signal_event.min_change_rate)
    return intraday.sync(c.stock_code for c in pool)


def settle_today_minutes() -> int:
    """마감 뒤 감시 풀의 오늘 봉을 확정해 지난 날 보관소에 넘긴다. 실패한 종목 수를 돌려준다.

    내일 아침 이 종목들의 "어제 봉"을 키움에서 다시 받지 않게 하려는 것이다.
    """
    pool = find_candidate_stocks(get_settings().signal_event.min_change_rate)
    settled, failures = intraday.settle(c.stock_code for c in pool)
    day = today()
    for code, bars in settled.items():
        minute_archive.put(code, day, bars)
    # 수정주가 소급이 반영되지 않는 봉을 오래 두지 않는다. 하루 한 번 여기서 지운다.
    try:
        minute_archive.purge(day - _MINUTE_ARCHIVE_RETENTION)
    except Exception:
        log.warning("지난 날 분봉 정리 실패", exc_info=True)
    return failures


#: 지난 날 분봉 보관 기간. 거래일로 열흘 남짓 — 3거래일 차트와 달력 20일에 넉넉하다.
_MINUTE_ARCHIVE_RETENTION = timedelta(days=14)


@ttl_cache("candidateStocks", ttl_seconds=5, maxsize=15)
def find_candidate_stocks(min_daily_price_change_rate: float) -> list[LeadingStockSnapshot]:
    """Phase 1 필터만 적용한 후보 (거래대금 순위 + 당일 등락률). 거래대금 내림차순.

    등락률 임계값은 사용자가 고르므로 **캐시 키도 그 값으로 분리**한다.

    거래대금 1·2위를 등락률과 무관하게 끼워 넣던 예외는 없앴다 — 목록 위쪽이 주도주
    구간으로 바뀌면서 대장주를 보여주는 일은 그쪽이 맡는다. 예외를 남겨두면 "거래대금 순"
    구간에 기준 미달 종목이 설명 없이 섞인다.
    """
    log.info("후보 종목 조회 (Phase 1) — 등락률 >= %s%%", min_daily_price_change_rate)
    candidates = _trading_value_pool()
    log.info("거래대금 순위에서 %d건 수집", len(candidates))

    # 사용자 지정 등락률만 덮어쓴 임계값으로 Phase 1 구성
    effective = _criteria().model_copy(update={"min_daily_price_change_rate": min_daily_price_change_rate})
    survivors = flt.FilterChain([
        flt.EtfExclusionFilter(),
        flt.SpacExclusionFilter(),
        flt.TradingValueRankFilter(effective),
        flt.DailyPriceChangeFilter(effective),
    ]).apply(candidates)
    log.info("Phase 1 통과 %d건", len(survivors))
    return survivors


def find_leaders(count: int) -> list[LeadingStockSnapshot]:
    """거래대금·등락률이 함께 높은 상위 `count`개 — 첫 화면이 쓴다.

    후보 목록과 달리 **사용자가 고른 등락률과 무관하다.** 첫 화면은 "오늘 뭐가 주도주냐"
    하나만 답하는 자리라, 보는 사람이 어떤 기준을 걸어뒀는지에 따라 달라지면 안 된다.
    임계값 0으로 후보를 받아(= 오른 종목 전부) 그 안에서 점수로 다시 세운다.
    """
    return LeadingStocks(find_candidate_stocks(0.0)).leaders(count)


def find_limit_ups() -> list[LeadingStockSnapshot]:
    """후보 풀 안의 상한가 — 첫 화면 주도주 카드가 쓴다.

    **주도주 탑5가 아니라 후보 풀 전체를 본다.** 상한가는 잠기면서 거래가 말라 거래대금
    점수가 낮아지므로, 탑5 안에서만 찾으면 대개 한 종목도 안 나온다.

    `find_candidate_stocks`가 캐시돼 있어 주도주와 같은 응답을 나눠 쓴다 — 키움을 두 번
    두드리지 않는다. 후보 컷이 거래대금 35위라 **시장 전체 상한가가 아니다**(화면도 그렇게 말한다).
    """
    return LeadingStocks(find_candidate_stocks(0.0)).limit_ups()


@ttl_cache(
    "stockBasics", ttl_seconds=24 * 60 * 60, maxsize=100,
    key=lambda stock_code: (stock_code, today(), now().time() >= _SESSION_START),
    skip_if=lambda value: value is None,
)
def _stock_basics(stock_code: str) -> LeadingStockSnapshot | None:
    """시총·시가·전일 종가를 보관한다. 개장 전에 읽은 값은 08:00 이후 한 번 갱신한다."""
    return kiwoom_market.fetch_stock_detail(stock_code)


@ttl_cache(
    "stockEvaluationHistory", ttl_seconds=24 * 60 * 60, maxsize=100,
    key=lambda stock_code: (stock_code, today()), skip_if=lambda candles: not candles,
)
def _evaluation_history(stock_code: str) -> list[DailyCandle]:
    """상세 조건용 전일까지 최근 60거래일. 차트의 당일 일봉 캐시와 수명을 분리한다."""
    current_day = today()
    return [
        candle for candle in kiwoom_market.fetch_daily_candles(stock_code, 61)
        if candle.date < current_day
    ][:60]


def evaluate_stock(stock_code: str) -> StockEvaluation:
    """모든 필터 평가 + 상대거래량 — 상세 보기용."""
    log.info("종목 평가: %s", stock_code)

    rank_info = next(
        (s for s in _trading_value_pool() if s.stock_code == stock_code),
        None,
    )
    current_day = today()
    history = _evaluation_history(stock_code)

    if rank_info is not None:
        basics = _stock_basics(stock_code)
        if basics is None:
            raise LookupError(f"종목을 찾을 수 없습니다: {stock_code}")
        stock = dataclasses.replace(
            rank_info,
            market_cap=basics.market_cap,
            opening_price=basics.opening_price,
            previous_close=basics.previous_close,
        )
    else:
        # 후보 풀 밖의 종목도 과거 시그널 등에서 열 수 있다. 그때만 기본정보로 시세를 받는다.
        stock = kiwoom_market.fetch_stock_detail(stock_code)
        if stock is None:
            raise LookupError(f"종목을 찾을 수 없습니다: {stock_code}")

    # 전일·시가 필터는 최신 봉을 오늘로 간주한다. 변하지 않는 값만 합성하고
    # 당일 거래량은 후보라면 거래대금 순위, 후보 밖이라면 기본정보 시세에서 받는다.
    daily = [
        DailyCandle(current_day, stock.opening_price, 0, 0, stock.current_price, 0, stock.price_change_rate),
        *history,
    ]
    criteria = _criteria()
    # 나열 순서가 곧 화면 표시 순서다. 판별력이 큰 것부터 둔다 —
    # 주도주를 정의하는 둘 → 진입 자리 → 과열 배제(상한) → 기준이 느슨해 대부분 통과하는 둘.
    all_filters: list[flt.StockFilter] = [
        # 주도주 정의 — 이 둘만 Phase 1 후보 선별에도 쓰인다
        flt.TradingValueRankFilter(criteria),
        flt.DailyPriceChangeFilter(criteria),
        # 지금 들어갈 자리인가
        flt.DailyHighPositionFilter(criteria, lambda _c: daily, current_day),
        flt.PriceAboveOpenFilter(),
        # 이미 다 간 종목 배제(상한)
        flt.PrevDayCloseFilter(criteria, lambda _c: daily[:3]),
        flt.OpeningPriceFilter(criteria, lambda _c: daily[:3]),
        # 하한이 느슨해 거래대금 상위면 대체로 통과한다
        flt.MarketCapFilter(criteria),
        flt.ProgramNetBuyFilter(criteria, kiwoom_program.fetch_program_net_buy),
    ]

    return StockEvaluation(
        stock=stock,
        filter_results=[f.evaluate(stock) for f in all_filters],
        relative_volume=DailyCandles(history).relative_volume_from(
            stock.accumulated_volume
            if stock.accumulated_volume is not None
            and now().time() >= _SESSION_START
            and calendar.is_open(current_day) is True
            else None,
            current_day, _RVOL_LOOKBACK_DAYS,
        ),
        # 소속 시장은 종목 카탈로그가 안다 — 시세에는 없는 정보라 stock 피처에 묻는다
        market=stock_app.market_of(stock_code),
    )


def breakout_radar() -> list[BreakoutRadarStock]:
    """후보를 **돌파선 근접 순**으로 정렬한다. 눌림선은 같은 행에 함께 싣는다.

    **등락률로 거르지 않는다.** 눌림은 원래 내린 종목에서 나오는 신호라, 상승률 하한을
    걸면 그쪽이 통째로 빈다. 시그널 폴러와 같은 감시 풀을 쓰므로 후보 조회도 분봉도
    캐시가 겹쳐, 풀을 넓혀도 브로커 호출은 늘지 않는다.
    """
    out = []
    for c in find_candidate_stocks(get_settings().signal_event.min_change_rate):
        candles = _breakout_high_candles(c.stock_code)
        signal = candles.peak_signal(c.current_price)
        if signal is None:
            continue
        # 지지선은 같은 분봉에서 대칭으로 뽑는다. 없더라도 저항은 보여준다.
        support = candles.trough_signal(c.current_price)
        out.append(
            BreakoutRadarStock(
                stock_code=c.stock_code,
                stock_name=c.stock_name,
                current_price=c.current_price,
                price_change_rate=c.price_change_rate,
                peak_price=signal.peak_price,
                peak_at=signal.peak_at,
                gap_rate=signal.gap_rate,
                trading_value=c.accumulated_trading_value,
                trough_price=support.trough_price if support else None,
                trough_at=support.trough_at if support else None,
                support_gap_rate=support.gap_rate if support else None,
            )
        )
    return sorted(out, key=lambda s: s.gap_rate)


def signal_readings(min_daily_price_change_rate: float) -> list[CandidateSignalReading]:
    """후보별 분봉 1회로 돌파 갭·전고점·스파이크 배율을 함께 읽는다.

    `spike_ratio`는 거래대금 임계를 넘긴 봉만 채운다 — 미달·봉없음이면 None으로 둬야
    히스테리시스 해제가 동작한다.
    """
    readings = []
    for c in find_candidate_stocks(min_daily_price_change_rate):
        # 돌파선은 최근 3거래일 연속 분봉으로, 스파이크는 당일만
        recent = _breakout_high_candles(c.stock_code)
        high = recent.peak_signal(c.current_price)
        spike = MinuteCandles(_latest_session_minute_candles(c.stock_code)).volume_spike(
            _SPIKE_BASELINE_BARS
        )
        if spike is not None and spike.latest_trading_value < _SPIKE_MIN_TRADING_VALUE:
            spike = None
        readings.append(
            CandidateSignalReading(
                stock_code=c.stock_code,
                stock_name=c.stock_name,
                current_price=c.current_price,
                price_change_rate=c.price_change_rate,
                trading_value=c.accumulated_trading_value,
                gap_rate=high.gap_rate if high else None,
                peak_price=high.peak_price if high else None,
                spike_ratio=spike.ratio if spike else None,
                minute_trading_value=spike.latest_trading_value if spike else None,
                spike_direction=spike.direction if spike else None,
            )
        )
    return readings


def minute_candles(stock_code: str, on: date) -> list[MinuteCandle]:
    """상세 차트용 — 최근 3거래일 1분봉, 시간 오름차순.

    오늘 봉은 토스(`_today_minute_candles`), 지난 날은 **날짜 단위 보관소**에서 먼저 찾고 없으면
    키움 ka10080에서 받는다. 어느 날이 거래일인지는 개장일 달력이 짚는다 — 보관소에 없는 날이
    휴장이라서인지 아직 안 받아서인지 가르려면 달력이 있어야 한다.

    달력이 모르는 날(목록은 20일 전부터 온다 — 시그널 로그의 오래된 날짜)은 예전 방식대로
    키움 페이지를 이어 붙인다.
    """
    current = today()
    days: dict[date, list[MinuteCandle]] = {}
    if on == current:
        today_bars = _today_minute_candles(stock_code)
        if today_bars:
            days[current] = today_bars
    # 오늘이면 어제부터, 지난 날이면 그날부터(그날이 거래일이면 포함) 거슬러 간다
    cursor = on if on == current else on + timedelta(days=1)
    while len(days) < _CHART_SESSION_DAYS:
        day = calendar.previous_open_day(cursor)
        if day is None:
            return _minute_candles_by_pages(stock_code, on)
        bars = _past_day_minutes(stock_code, day)
        if not bars:
            break  # 그날 봉을 못 받았다 — 있는 만큼만 보여준다
        days[day] = bars
        cursor = day
    return [c for day in sorted(days) for c in days[day]]


def _past_day_minutes(stock_code: str, day: date) -> list[MinuteCandle]:
    """지난 거래일 하루치. 보관소에 없으면 그날을 기준일로 키움 페이지를 받고, 완성된 날들을 넣어 둔다."""
    archived = minute_archive.get(stock_code, day)
    if archived is not None:
        return archived
    page = kiwoom_market.fetch_historical_minute_candles(stock_code, day)
    minute_archive.put_page(stock_code, page)
    return sorted((c for c in page if c.date_time.date() == day), key=lambda c: c.date_time)


def _minute_candles_by_pages(stock_code: str, on: date) -> list[MinuteCandle]:
    """달력이 없을 때 — 키움 페이지의 **가장 이른 날**을 기준일로 이어 호출하며 거래일을 채운다.

    ka10080 한 페이지는 직전일 일부까지만 닿는다. **지난 날 조회의 기준일은 오늘보다 앞이어야
    한다** — 과거 분봉은 4일 캐시라 오늘을 기준일로 부르면 형성 중인 오늘 봉이 굳는다. 토스는
    오늘 봉만 주므로 첫 기준일을 어제로 잡고, 어제가 휴장이면 키움이 그 전 거래일부터 채워 준다.
    """
    current = today()
    yesterday = current - timedelta(days=1)
    seed = (
        _today_minute_candles(stock_code) + kiwoom_market.fetch_historical_minute_candles(stock_code, yesterday)
        if on == current
        else kiwoom_market.fetch_historical_minute_candles(stock_code, on)
    )
    all_candles = list(seed)

    def 날짜수(candles) -> int:
        return len({c.date_time.date() for c in candles})

    while 날짜수(all_candles) <= _CHART_SESSION_DAYS:
        if not all_candles:
            break
        oldest = min(min(c.date_time.date() for c in all_candles), yesterday)
        before = 날짜수(all_candles)
        all_candles.extend(kiwoom_market.fetch_historical_minute_candles(stock_code, oldest))
        if 날짜수(all_candles) == before:
            break  # 더 과거가 없다

    # 기준일 이하의 최근 거래일만 — 과거 날짜 조회 시 그 날짜까지로 한정
    recent_days = set(
        sorted({c.date_time.date() for c in all_candles if c.date_time.date() <= on}, reverse=True)[
            :_CHART_SESSION_DAYS
        ]
    )
    by_time: dict[datetime, MinuteCandle] = {}
    for c in all_candles:
        if c.date_time.date() in recent_days:
            by_time.setdefault(c.date_time, c)
    return [by_time[t] for t in sorted(by_time)]


def daily_candles(stock_code: str, on: date) -> list[DailyCandle]:
    """일봉 차트용 — 기준일 이전 200거래일."""
    return kiwoom_market.fetch_daily_candles(stock_code, 200, on)


def _breakout_high_candles(stock_code: str) -> MinuteCandles:
    """돌파선용 분봉 — 차트와 동일한 경로를 재활용해 캐시를 공유한다."""
    return MinuteCandles(minute_candles(stock_code, today()))


def _latest_session_minute_candles(stock_code: str) -> list[MinuteCandle]:
    """오늘 분봉만 — 스파이크는 당일 봉끼리만 비교한다.

    폴러는 장중(08~20시)에만 돌아 오늘 봉이 있다. 그 밖에는 빈 목록이라 스파이크가 없다.
    """
    return _today_minute_candles(stock_code)


#: 장중에 읽는 오늘 분봉은 이보다 오래되지 않아야 한다. 감시 풀은 20초마다 채워져 대개 걸리지 않는다.
_FRESH_DURING_SESSION = timedelta(seconds=30)
#: 마감(20:00) 뒤 이 시각부터 받은 오늘 봉은 확정본이다 — 마지막 봉이 굳을 1분을 둔다.
_SETTLED_AFTER_CLOSE = time(20, 1)


def _today_minute_candles(stock_code: str) -> list[MinuteCandle]:
    """오늘 1분봉. 당일 저장소에서 꺼내고, 낡았으면 그 자리에서 새 봉만 이어 받는다.

    - 휴장일·08:00 전: 오늘 봉이 있을 수 없다 — 부르지 않는다.
    - 마감 확정 뒤: 지난 날 보관소에 넘어간 확정본을 쓴다. 없으면 한 번 받아 확정본으로 넘긴다.
    - 장중: 30초 안에 받은 값이면 그대로, 아니면 이어 받는다.
    """
    holiday, _ = calendar.market_status()
    at = now()
    if holiday or at.time() < _SESSION_START:
        return []
    settled = minute_archive.get(stock_code, today())
    if settled is not None:
        return settled
    if at.time() < _SETTLED_AFTER_CLOSE:
        return intraday.ensure(stock_code, at - _FRESH_DURING_SESSION)
    closed_at = datetime.combine(at.date(), _SETTLED_AFTER_CLOSE)
    bars = intraday.ensure(stock_code, closed_at)
    # 마감이 굳은 뒤에 받는 데 **성공했을 때만** 확정본이다 — 실패해 돌려받은 옛 봉은 넘기지 않는다
    if intraday.synced_since(stock_code, closed_at):
        minute_archive.put(stock_code, today(), bars)
    return bars


def previous_trading_day(on: date) -> date:
    d = on - timedelta(days=1)
    while d.weekday() >= 5:
        d -= timedelta(days=1)
    return d
