"""주도주 폴러 — 종목 시그널(10s) / 시장 시그널(30s) / 지수 반등(30s) / 마감 스냅샷(15:40).

폴러는 **화면을 아무도 안 보고 있어도** 쌓이게 하려고 서버가 능동적으로 돈다.
직전 상태는 메모리에 들고 일자가 바뀌면 버린다. 흐름 전환 정점만 DB에 남겨 재시작에도 복원한다.
"""

import logging
import threading
from datetime import date, datetime, time, timedelta

from apscheduler.schedulers.base import BaseScheduler
from apscheduler.triggers.cron import CronTrigger
from apscheduler.triggers.interval import IntervalTrigger

from backend.leadingstock import application, events
from backend.leadingstock.domain import IndexMinuteCandle, IndexMinuteCandles
from backend.leadingstock.entities import (
    MarketSignalEvent,
    SignalEvent,
    buffer_eok,
    reversal_eok,
    step_eok,
)
from backend.leadingstock.infrastructure import MarketInvestorSnapshot
from backend.leadingstock.signals import (
    InvestorFlowState,
    InvestorNetBuyState,
    InvestorType,
    MarketSignalType,
    NetTradeSide,
    SignalEventType,
    SignalReading,
    SignalState,
)
from backend.library.db import get_session_factory
from backend.library.time import KST, now, today
from backend.market import calendar
from backend.platform.kiwoom import index as kiwoom_index
from backend.settings import get_settings
from backend.stock.domain import Market

log = logging.getLogger(__name__)

_SNAPSHOT_START = time(8, 0)   # NXT 프리마켓 개장
_SNAPSHOT_END = time(20, 0)    # NXT 애프터마켓 마감
_MA_INTERVAL_MINUTES = 5
_MA_PERIOD = 20
_MA_REARM_MARGIN = 0.005

_lock = threading.Lock()


def _session_window() -> tuple[time, time]:
    s = get_settings().market_signal
    return time.fromisoformat(s.session_start), time.fromisoformat(s.session_end)


# ── 종목 시그널 폴러 ────────────────────────────────────────────────────

_signal_states: dict[str, SignalState] = {}
_last_fired: dict[str, datetime] = {}
_signal_trade_date: date | None = None


def poll_signal_events() -> None:
    holiday, trading_hours = calendar.market_status()
    if holiday or not trading_hours:
        return
    try:
        _detect_signal_events()
    except Exception:
        log.warning("시그널 전이 적재 실패", exc_info=True)


def _detect_signal_events() -> None:
    global _signal_trade_date
    current = today()
    with _lock:
        if _signal_trade_date != current:  # 일자 전환 — 직전 상태 폐기
            _signal_states.clear()
            _last_fired.clear()
            _signal_trade_date = current

    settings = get_settings().signal_event
    at = now()
    cooldown_from = at - timedelta(minutes=settings.cooldown_minutes)

    recorded: list[SignalEvent] = []
    for r in application.signal_readings(settings.min_change_rate):
        prev = _signal_states.get(r.stock_code, SignalState())
        fired, next_state = prev.advance(
            SignalReading(
                r.spike_ratio,
                r.ma20_crossed_up, r.ma20_crossed_down, r.ma20_below_band, r.ma20_above_band,
            )
        )
        _signal_states[r.stock_code] = next_state
        if not fired:
            continue
        for event_type in fired:
            # 같은 종목·타입이 쿨다운 안에 또 뜨면 중복으로 보고 스킵 (프리마켓 출렁임 대응)
            key = f"{r.stock_code}|{event_type.value}"
            last = _last_fired.get(key)
            if last is not None and last > cooldown_from:
                continue
            _last_fired[key] = at
            recorded.append(_to_signal_event(event_type, r, at, current))

    if recorded:
        with get_session_factory()() as session:
            events.record_signal_events(session, recorded)
        log.info("시그널 전이 %d건 적재", len(recorded))


def _to_signal_event(event_type: SignalEventType, r, at: datetime, on: date) -> SignalEvent:
    spike = event_type is SignalEventType.VOLUME_SPIKE
    return SignalEvent(
        occurred_at=at, trade_date=on,
        stock_code=r.stock_code, stock_name=r.stock_name,
        event_type=event_type.value,
        current_price=r.current_price, price_change_rate=r.price_change_rate,
        trading_value=r.trading_value,
        gap_rate=None,  # 돌파 이벤트가 사라져 항상 비어 있다(컬럼은 이력 때문에 남김)
        spike_ratio=r.spike_ratio if spike else None,
        minute_trading_value=r.minute_trading_value if spike else None,
        spike_direction=r.spike_direction if spike else None,
        ma20=r.ma20 if event_type in _MA20_EVENTS else None,
        created_at=at, updated_at=at,
    )


_MA20_EVENTS = (SignalEventType.MA20_REBOUND, SignalEventType.MA20_BREAKDOWN)


# ── 시장 시그널 폴러 ────────────────────────────────────────────────────

_level_states: dict[str, InvestorNetBuyState] = {}
_flow_states: dict[str, InvestorFlowState] = {}
_market_trade_date: date | None = None


def poll_market_signal_events() -> None:
    if calendar.is_holiday(calendar.Region.KR):
        return
    if not (_SNAPSHOT_START <= now().time() <= _SNAPSHOT_END):
        return
    try:
        _detect_market_signals()
    except Exception:
        log.warning("시장 시그널 적재 실패", exc_info=True)


def _detect_market_signals() -> None:
    """정규장 밖(프리·애프터)엔 **순매수 스냅샷만** 남기고 시그널은 내지 않는다.

    세션 표의 프리·애프터 구간을 스냅샷 경계 diff로 만들려면 정규장 밖 누적도 필요하다.
    """
    global _market_trade_date
    current = today()
    with _lock:
        if _market_trade_date != current:
            _level_states.clear()
            _flow_states.clear()
            _market_trade_date = current

    at = now()
    signal_start, signal_end = _session_window()
    emit_signals = signal_start <= at.time() <= signal_end

    with get_session_factory()() as session:
        # 재시작으로 메모리가 비었으면 그날 흐름 전환 정점을 DB에서 복원
        if not _flow_states:
            _flow_states.update(events.load_flow_states(session, current))

        snapshots: list[MarketInvestorSnapshot] = []
        recorded: list[MarketSignalEvent] = []

        for market, nb in events.investor_net_buy().items():
            snapshots.append(
                MarketInvestorSnapshot(
                    market=market, trade_date=current, captured_at=at,
                    foreign_eok=nb.foreign_eok, institution_eok=nb.institution_eok,
                    individual_eok=nb.individual_eok, other_corp_eok=nb.other_corp_eok,
                    financial_investment_eok=nb.financial_investment_eok, trust_eok=nb.trust_eok,
                    pension_fund_eok=nb.pension_fund_eok, private_equity_eok=nb.private_equity_eok,
                    insurance_eok=nb.insurance_eok, bank_eok=nb.bank_eok,
                    other_finance_eok=nb.other_finance_eok,
                    created_at=at, updated_at=at,
                )
            )
            if not emit_signals:
                continue

            step, buffer, reversal = step_eok(market), buffer_eok(market), reversal_eok(market)
            for investor, net_eok in (
                (InvestorType.FOREIGN, nb.foreign_eok),
                (InvestorType.INSTITUTION, nb.institution_eok),
                (InvestorType.INDIVIDUAL, nb.individual_eok),
            ):
                key = f"{market.name}|{investor.name}"

                # 1) 순매수 단계 — 그날 같은 조합은 한 번만(재시작·회복 재발화 방지)
                transition, next_level = _level_states.get(key, InvestorNetBuyState()).advance(
                    net_eok, step, buffer
                )
                _level_states[key] = next_level
                if transition is not None and not events.already_fired_net_buy_level(
                    session, current, market, investor, transition.side, transition.level
                ):
                    recorded.append(
                        _market_event(
                            MarketSignalType.NET_BUY_LEVEL, at, current, market, transition.side,
                            investor=investor, level=transition.level, net_amount_eok=net_eok,
                            index_value=nb.index_value, change_rate=nb.change_rate,
                        )
                    )

                # 2) 흐름 전환 — 정점에서 임계 이상 되돌리면 방향 꺾임
                turn, next_flow = _flow_states.get(key, InvestorFlowState()).advance(net_eok, reversal)
                _flow_states[key] = next_flow
                # 정점을 DB에 보존 — 재시작 시 복원해 전환을 놓치지 않게
                events.save_flow_state(session, current, market, investor, next_flow)
                if turn is not None:
                    recorded.append(
                        _market_event(
                            MarketSignalType.NET_FLOW_TURN, at, current, market, turn.to,
                            investor=investor, net_amount_eok=net_eok,
                            extreme_amount_eok=turn.extreme_eok,
                            index_value=nb.index_value, change_rate=nb.change_rate,
                        )
                    )

        events.record_investor_snapshots(session, snapshots)
        events.record_market_events(session, recorded)

    if recorded:
        log.info("시장 시그널 %d건 적재", len(recorded))


def _market_event(
    kind: MarketSignalType, at: datetime, on: date, market: Market, side: NetTradeSide,
    investor: InvestorType | None = None, level: int | None = None,
    net_amount_eok: int | None = None, extreme_amount_eok: int | None = None,
    index_value: float | None = None, change_rate: float | None = None,
) -> MarketSignalEvent:
    return MarketSignalEvent(
        occurred_at=at, trade_date=on, kind=kind.value, market=market, side=side,
        investor=investor, level=level, net_amount_eok=net_amount_eok,
        extreme_amount_eok=extreme_amount_eok, index_value=index_value, change_rate=change_rate,
        created_at=at, updated_at=at,
    )


# ── 지수 반등·꺾임 폴러 ─────────────────────────────────────────────────

_rebound_armed: dict[Market, bool] = {}
_breakdown_armed: dict[Market, bool] = {}
_index_store: dict[Market, dict[datetime, IndexMinuteCandle]] = {}
_index_loaded: set[Market] = set()
_index_trade_date: date | None = None


def poll_index_rebound() -> None:
    if calendar.is_holiday(calendar.Region.KR):
        return
    signal_start, signal_end = _session_window()
    if not (signal_start <= now().time() <= signal_end):  # 지수는 정규장에만 체결
        return
    try:
        _detect_index_rebound()
    except Exception:
        log.warning("지수 반등 적재 실패", exc_info=True)


def _detect_index_rebound() -> None:
    """반등/꺾임은 각자 무장 상태에서 해당 방향 돌파봉이 나올 때 1회 발화하고 해제한다.

    반대로 이평 대비 마진 이상 밀려야 재무장해 잔떨림을 막는다.
    """
    global _index_trade_date
    current = today()
    with _lock:
        if _index_trade_date != current:  # 일자 전환 — 무장 상태 초기화
            _rebound_armed.clear()
            _breakdown_armed.clear()
            _index_store.clear()
            _index_loaded.clear()
            _index_trade_date = current

    at = now()
    recorded: list[MarketSignalEvent] = []

    with get_session_factory()() as session:
        for market in Market:
            intraday = kiwoom_index.fetch_index_intraday_for(market, current)
            if intraday is None:
                continue

            # 매 폴마다 받은 최근 ~4분치를 누적(겹치며 하루를 채운다). 백필 없음 — 한도 보호.
            fresh = IndexMinuteCandles.from_ticks(intraday.ticks).candles()
            store = _merge_index_candles(session, market, current, fresh)
            # 누적 저장소 기준 판정 — 4분 페이지 경계에서 이평이 잘리지 않게
            ma = IndexMinuteCandles(store).moving_average(
                _MA_INTERVAL_MINUTES, _MA_PERIOD, _MA_REARM_MARGIN
            )
            if ma is None:
                continue

            if _rebound_armed.get(market, True) and ma.crossed_up:
                _rebound_armed[market] = False
                recorded.append(
                    _market_event(MarketSignalType.MA20_REBOUND, at, current, market, NetTradeSide.BUY,
                                  index_value=intraday.value, change_rate=intraday.change_rate)
                )
            elif ma.below_band:
                _rebound_armed[market] = True

            if _breakdown_armed.get(market, True) and ma.crossed_down:
                _breakdown_armed[market] = False
                recorded.append(
                    _market_event(MarketSignalType.MA20_BREAKDOWN, at, current, market, NetTradeSide.SELL,
                                  index_value=intraday.value, change_rate=intraday.change_rate)
                )
            elif ma.above_band:
                _breakdown_armed[market] = True

        events.record_market_events(session, recorded)

    if recorded:
        log.info("지수 반등·꺾임 %d건 적재", len(recorded))


def _merge_index_candles(session, market: Market, on: date, fresh: list[IndexMinuteCandle]):
    """메모리에 병합하고 같은 값을 DB에 라이트스루한다. 재시작 직후엔 그날치를 한 번 끌어온다."""
    if market not in _index_loaded:
        _index_loaded.add(market)
        _index_store.setdefault(market, {}).update(
            {c.minute: c for c in events.index_candles_on(session, market, on)}
        )
    store = _index_store.setdefault(market, {})
    store.update({c.minute: c for c in fresh})
    events.upsert_index_candles(session, market, fresh)
    return [store[m] for m in sorted(store)]


def register(scheduler: BaseScheduler) -> None:
    s = get_settings()
    scheduler.add_job(
        poll_signal_events,
        IntervalTrigger(seconds=s.signal_event.poll_interval_millis / 1000),
        id="signal-event-poller", replace_existing=True,
    )
    scheduler.add_job(
        poll_market_signal_events,
        IntervalTrigger(seconds=s.market_signal.poll_interval_millis / 1000),
        id="market-signal-event-poller", replace_existing=True,
    )
    scheduler.add_job(
        poll_index_rebound,
        IntervalTrigger(seconds=s.market_signal.candle_poll_interval_millis / 1000),
        id="index-rebound-poller", replace_existing=True,
    )
