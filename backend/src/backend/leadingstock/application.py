"""주도주 후보 발굴·평가.

Phase 1(거래대금 순위 + 당일 등락률)로 후보를 추리고, 상세 보기에서 전체 필터를 평가한다.
분봉은 차트·돌파선·시그널이 **같은 캐시를 공유**하도록 한 경로로 모은다 — 따로 부르면
키움 rate limit에 걸린다.
"""

import dataclasses
import logging
from dataclasses import dataclass
from datetime import date, datetime, timedelta

from backend.leadingstock import filters as flt
from backend.leadingstock.domain import (
    DailyCandle,
    DailyCandles,
    LeadingStockSnapshot,
    MinuteCandle,
    MinuteCandles,
    SpikeDirection,
)
from backend.library.cache import ttl_cache
from backend.library.time import today
from backend.platform.kiwoom import market as kiwoom_market
from backend.platform.kiwoom import program as kiwoom_program
from backend.settings import get_settings

log = logging.getLogger(__name__)

_TOP_RANK_ALWAYS_INCLUDED = 2
_RVOL_LOOKBACK_DAYS = 20
_SPIKE_BASELINE_BARS = 20             # 직전 평균 산정 봉 수
_SPIKE_MIN_TRADING_VALUE = 1_000_000_000  # 최신 1분봉 최소 거래대금(원)
_MA_INTERVAL_MINUTES = 5              # 돌림 판정 분봉 주기
_MA_PERIOD = 20                       # 돌림 판정 이평 기간(봉)
_MA_REARM_MARGIN = 0.005              # 돌림 재무장 마진(0.5%)
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
    ma_crossed_up: bool | None
    ma_crossed_down: bool | None
    ma_below_band: bool | None
    ma_above_band: bool | None
    ma: int | None


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
    """종목 상세 평가 — 필터 평가 + 상대거래량. 데이터 없으면 각각 None."""

    stock: LeadingStockSnapshot
    filter_results: list[flt.FilterEvaluationResult]
    relative_volume: float | None


def _criteria():
    return get_settings().criteria


@ttl_cache("candidateStocks", ttl_seconds=5, maxsize=15)
def find_candidate_stocks(min_daily_price_change_rate: float) -> list[LeadingStockSnapshot]:
    """Phase 1 필터만 적용한 후보 (거래대금 순위 + 당일 등락률).

    등락률 임계값은 사용자가 고르므로 **캐시 키도 그 값으로 분리**한다.
    """
    log.info("후보 종목 조회 (Phase 1) — 등락률 >= %s%%", min_daily_price_change_rate)
    candidates = kiwoom_market.fetch_top_trading_value_stocks(50)
    log.info("거래대금 순위에서 %d건 수집", len(candidates))

    etf, spac = flt.EtfExclusionFilter(), flt.SpacExclusionFilter()

    # 개별종목 거래대금 1~2위는 등락률 무관 항상 포함 — 시장 톤 기준점 (ETF·스팩은 제외)
    top_ranks = [c for c in candidates if etf.filter(c) and spac.filter(c)][:_TOP_RANK_ALWAYS_INCLUDED]

    # 사용자 지정 등락률만 덮어쓴 임계값으로 Phase 1 구성
    effective = _criteria().model_copy(update={"min_daily_price_change_rate": min_daily_price_change_rate})
    survivors = flt.FilterChain([
        etf,
        spac,
        flt.TradingValueRankFilter(effective),
        flt.DailyPriceChangeFilter(effective),
    ]).apply(candidates)

    # 거래대금 순 정렬 유지 + 중복 제거 (강제 포함분이 survivors와 겹치면 자연 dedupe)
    merged: list[LeadingStockSnapshot] = []
    seen: set[str] = set()
    for stock in [*top_ranks, *survivors]:
        if stock.stock_code not in seen:
            seen.add(stock.stock_code)
            merged.append(stock)
    log.info(
        "Phase 1 통과 %d건 (거래대금 상위 %d개 강제 포함 + 필터 통과 %d건)",
        len(merged), _TOP_RANK_ALWAYS_INCLUDED, len(survivors),
    )
    return merged


def evaluate_stock(stock_code: str) -> StockEvaluation:
    """모든 필터 평가 + 상대거래량 — 상세 보기용."""
    log.info("종목 평가: %s", stock_code)

    rank_info = next(
        (s for s in kiwoom_market.fetch_top_trading_value_stocks(50) if s.stock_code == stock_code),
        None,
    )
    base = kiwoom_market.fetch_stock_detail(stock_code)
    if base is None:
        raise LookupError(f"종목을 찾을 수 없습니다: {stock_code}")

    stock = (
        dataclasses.replace(
            base,
            trading_value_rank=rank_info.trading_value_rank,
            accumulated_trading_value=rank_info.accumulated_trading_value,
        )
        if rank_info is not None
        else base
    )

    # 일봉 1회 조회 — 고가 위치·전일 등락률·시초가 필터가 공유한다
    daily = kiwoom_market.fetch_daily_candles(stock_code, 60)
    criteria = _criteria()
    # 나열 순서가 곧 화면 표시 순서다. 판별력이 큰 것부터 둔다 —
    # 주도주를 정의하는 둘 → 진입 자리 → 과열 배제(상한) → 기준이 느슨해 대부분 통과하는 둘.
    all_filters: list[flt.StockFilter] = [
        # 주도주 정의 — 이 둘만 Phase 1 후보 선별에도 쓰인다
        flt.TradingValueRankFilter(criteria),
        flt.DailyPriceChangeFilter(criteria),
        # 지금 들어갈 자리인가
        flt.DailyHighPositionFilter(criteria, lambda _c: daily),
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
        relative_volume=DailyCandles(daily).relative_volume(today(), _RVOL_LOOKBACK_DAYS),
    )


def breakout_radar(min_daily_price_change_rate: float) -> list[BreakoutRadarStock]:
    """후보를 **저항선 근접 순**으로 정렬한다. 지지선은 같은 행에 함께 싣는다."""
    out = []
    for c in find_candidate_stocks(min_daily_price_change_rate):
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
        # 돌파선·돌림은 최근 3거래일 연속 분봉으로(5분봉 20이평이 개장부터 연속되게), 스파이크는 당일만
        recent = _breakout_high_candles(c.stock_code)
        high = recent.peak_signal(c.current_price)
        ma_reading = recent.moving_average(_MA_INTERVAL_MINUTES, _MA_PERIOD, _MA_REARM_MARGIN)
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
                ma_crossed_up=ma_reading.crossed_up if ma_reading else None,
                ma_crossed_down=ma_reading.crossed_down if ma_reading else None,
                ma_below_band=ma_reading.below_band if ma_reading else None,
                ma_above_band=ma_reading.above_band if ma_reading else None,
                ma=ma_reading.ma if ma_reading else None,
            )
        )
    return readings


def minute_candles(stock_code: str, on: date) -> list[MinuteCandle]:
    """상세 차트용 — 최근 3거래일 1분봉, 시간 오름차순.

    ka10080 한 페이지는 직전일 일부까지만 닿으므로, 가진 데이터의 **가장 이른 날**을
    base_dt로 이어 호출하며 거래일을 하나씩 채운다.
    """
    seed = (
        kiwoom_market.fetch_minute_candles(stock_code)
        if on == today()
        else kiwoom_market.fetch_historical_minute_candles(stock_code, on)
    )
    all_candles = list(seed)

    def 날짜수(candles) -> int:
        return len({c.date_time.date() for c in candles})

    while 날짜수(all_candles) <= _CHART_SESSION_DAYS:
        if not all_candles:
            break
        oldest = min(c.date_time.date() for c in all_candles)
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
    """가장 최근 거래일의 분봉만 추린다.

    ka10080은 base_dt 기준 과거 여러 날을 함께 내려주므로, 최신 거래일로 걸러야
    **전고점이 다른 날 봉에서 잡히지 않는다**(장중엔 당일, 마감 후엔 직전 세션).
    """
    candles = kiwoom_market.fetch_minute_candles(stock_code)
    if not candles:
        return []
    latest = max(c.date_time.date() for c in candles)
    return [c for c in candles if c.date_time.date() == latest]


def previous_trading_day(on: date) -> date:
    d = on - timedelta(days=1)
    while d.weekday() >= 5:
        d -= timedelta(days=1)
    return d
