"""주도주 도메인 값 객체.

M5 본체(필터·시그널)는 아직이다. **platform 어댑터가 반환하는 타입만** 선행해서 둔다 —
Kotlin도 `KiwoomMarketClient`·`KiwoomIndexClient`가 이 타입들을 그대로 돌려준다.
아키텍처 규칙상 상위 피처가 발행한 값 객체를 하위가 쓰는 것은 허용된다(행위 결합이 아니라 데이터 교환).
"""

from dataclasses import dataclass
from datetime import date, datetime


@dataclass(frozen=True)
class LeadingStockSnapshot:
    """주도주 후보 종목의 시점 스냅샷. 키움으로 수집한 시세·랭킹·기본 정보.

    상장 종목 카탈로그 `stock.domain.Stock`과는 별개 개념이라 "Snapshot"으로 구분한다.
    """

    stock_code: str
    stock_name: str
    current_price: int
    price_change_rate: float
    trading_value_rank: int
    accumulated_trading_value: int
    market_cap: int = 0
    opening_price: int = 0
    previous_close: int = 0
    high_price: int = 0
    low_price: int = 0
    program_net_buy: int = 0


@dataclass(frozen=True)
class DailyCandle:
    date: date
    open_price: int
    high_price: int
    low_price: int
    close_price: int
    volume: int
    change_rate: float  # 등락률 (%)


@dataclass(frozen=True)
class MinuteCandle:
    date_time: datetime
    open_price: int
    high_price: int
    low_price: int
    close_price: int
    volume: int
    trading_value: int


@dataclass(frozen=True)
class IndexTick:
    """업종 지수 한 시점의 시세(10초 틱). `value`는 지수값, `volume`은 그 틱 거래량."""

    at: datetime
    value: float
    volume: int = 0


# ── 캔들 컬렉션 ─────────────────────────────────────────────────────────

import math  # noqa: E402
from enum import Enum  # noqa: E402


class SpikeDirection(Enum):
    """스파이크 봉의 방향 — 종가>시가면 매수, 종가<시가면 매도, 같으면 보합."""

    BUY = "BUY"
    SELL = "SELL"
    FLAT = "FLAT"


@dataclass(frozen=True)
class SwingHighSignal:
    """당일 고가(전고점) 돌파 매매 시그널.

    `gap_rate` = (고점 − 현재가) / 현재가 × 100 — 전고점까지 남은 상승률(%).
    이미 도달·돌파했으면 0 이하.
    """

    peak_price: int
    peak_at: datetime
    gap_rate: float


@dataclass(frozen=True)
class VolumeSpike:
    """최신 1분봉 거래대금이 직전 평균 대비 몇 배인지. 1.0이면 평소 수준."""

    latest_trading_value: int
    at: datetime
    ratio: float
    direction: SpikeDirection


@dataclass(frozen=True)
class SwingLowSignal:
    """당일 저가(지지선) 근접 시그널. `SwingHighSignal`의 대칭.

    `gap_rate` = (현재가 − 저가) / 현재가 × 100 — 저가까지 남은 하락률(%).
    이미 닿았거나 깨고 내려갔으면 0 이하.
    """

    trough_price: int
    trough_at: datetime
    gap_rate: float


@dataclass(frozen=True)
class MovingAverageReading:
    """분봉 이평 교차 판정. `ma`는 최신 확정봉 시점 이평값(원, 반올림).

    `*_band`는 히스테리시스 재무장용이다 — 한 번 발화한 뒤 반대편으로 마진만큼
    벗어나야 다시 무장한다. 이평 근처에서 흔들릴 때 같은 사건이 반복 적재되는 걸 막는다.
    """

    crossed_up: bool
    crossed_down: bool
    below_band: bool
    above_band: bool
    ma: int


class MinuteCandles:
    """당일 분봉 모음 — 시간 오름차순으로 정규화해 보관한다."""

    def __init__(self, candles: list[MinuteCandle]) -> None:
        self._ordered = sorted(candles, key=lambda c: c.date_time)

    def day_high_signal(self, current_price: int) -> SwingHighSignal | None:
        """당일 분봉 중 최고가를 전고점(돌파 대상 저항)으로 본다.

        동일 최고가가 여러 번 나오면 **처음 형성된 봉**을 형성 시각으로 잡는다.
        """
        if current_price <= 0 or not self._ordered:
            return None
        peak = max(self._ordered, key=lambda c: c.high_price)  # 동점이면 앞의 것
        return SwingHighSignal(
            peak_price=peak.high_price,
            peak_at=peak.date_time,
            gap_rate=(peak.high_price - current_price) / current_price * 100,
        )

    def day_low_signal(self, current_price: int) -> SwingLowSignal | None:
        """당일 분봉 중 최저가를 지지선으로 본다. `day_high_signal`과 같은 방식이다.

        동일 최저가가 여러 번 나오면 **처음 형성된 봉**을 형성 시각으로 잡는다.
        """
        if current_price <= 0 or not self._ordered:
            return None
        trough = min(self._ordered, key=lambda c: c.low_price)  # 동점이면 앞의 것
        return SwingLowSignal(
            trough_price=trough.low_price,
            trough_at=trough.date_time,
            gap_rate=(current_price - trough.low_price) / current_price * 100,
        )

    def volume_spike(self, baseline_bars: int) -> VolumeSpike | None:
        """봉이 2개 미만이거나 직전 평균이 0이면 None."""
        if len(self._ordered) < 2:
            return None
        latest = self._ordered[-1]
        baseline = self._ordered[:-1][-baseline_bars:]
        avg = sum(c.trading_value for c in baseline) / len(baseline)
        if avg <= 0:
            return None
        return VolumeSpike(
            latest_trading_value=latest.trading_value,
            at=latest.date_time,
            ratio=latest.trading_value / avg,
            direction=_spike_direction(latest),
        )

    def moving_average(
        self, interval_minutes: int, period: int, rearm_margin: float
    ) -> MovingAverageReading | None:
        """확정 봉 이력에서 최신 확정봉이 이평을 아래→위로 돌파한 봉인지 직접 판정한다.

        마지막 봉은 진행 중이라 뺀다. 직전 확정봉의 이평까지 필요해 확정 봉이
        `period`+1개 미만이면 None. 폴러 관측 이력이 아니라 **봉 데이터 자체**로 크로스를
        잡으므로, 방금 후보에 든 종목도 최신 확정봉이 크로스면 잡힌다.
        """
        bars = self._aggregate(interval_minutes)[:-1]
        if len(bars) < period + 1:
            return None
        latest, prev = bars[-1], bars[-2]
        ma_latest = sum(b.close_price for b in bars[-period:]) / period
        ma_prev = sum(b.close_price for b in bars[-period - 1 : -1]) / period
        return MovingAverageReading(
            crossed_up=prev.close_price <= ma_prev and latest.close_price > ma_latest,
            crossed_down=prev.close_price >= ma_prev and latest.close_price < ma_latest,
            below_band=latest.close_price < ma_latest * (1 - rearm_margin),
            above_band=latest.close_price > ma_latest * (1 + rearm_margin),
            # Java `Math.round`는 floor(x+0.5) — Python 기본 round()의 은행가 반올림과 갈린다.
            ma=math.floor(ma_latest + 0.5),
        )

    def _aggregate(self, interval_minutes: int) -> list[MinuteCandle]:
        """구간 경계로 묶어 시가=첫봉, 종가=끝봉, 고저=구간 극값, 거래량·대금=합으로 합성."""
        groups: dict[datetime, list[MinuteCandle]] = {}
        for c in self._ordered:
            start = c.date_time.replace(
                minute=c.date_time.minute // interval_minutes * interval_minutes,
                second=0, microsecond=0,
            )
            groups.setdefault(start, []).append(c)
        return [
            MinuteCandle(
                date_time=start,
                open_price=group[0].open_price,
                high_price=max(g.high_price for g in group),
                low_price=min(g.low_price for g in group),
                close_price=group[-1].close_price,
                volume=sum(g.volume for g in group),
                trading_value=sum(g.trading_value for g in group),
            )
            for start, group in sorted(groups.items())
        ]


def _spike_direction(candle: MinuteCandle) -> SpikeDirection:
    if candle.close_price > candle.open_price:
        return SpikeDirection.BUY
    if candle.close_price < candle.open_price:
        return SpikeDirection.SELL
    return SpikeDirection.FLAT


class DailyCandles:
    """일봉 모음 — 입력은 **최신순(내림차순)**을 가정한다."""

    def __init__(self, candles: list[DailyCandle]) -> None:
        self._candles = candles

    def relative_volume(self, today: date, lookback: int) -> float | None:
        """풀데이 상대거래량(RVOL) = 당일 누적 거래량 / 직전 N거래일 평균.

        장 초반엔 당일 누적이 아직 적어 1 미만으로 낮게 나온다(풀데이 방식의 한계).
        """
        today_candle = next((c for c in self._candles if c.date == today), None)
        if today_candle is None:
            return None
        baseline = [c.volume for c in self._candles if c.date < today][:lookback]
        if not baseline:
            return None
        avg = sum(baseline) / len(baseline)
        if avg <= 0:
            return None
        return today_candle.volume / avg


# ── 지수 1분봉 ──────────────────────────────────────────────────────────


@dataclass(frozen=True)
class IndexMinuteCandle:
    """합성된 지수 1분봉 — 그 분 첫 틱이 시가, 마지막 틱이 종가, 고저는 분 내 극값."""

    minute: datetime
    open: float
    high: float
    low: float
    close: float
    volume: int


@dataclass(frozen=True)
class IndexMaSignal:
    """지수 분봉 이평 돌파 판정. 반등(상향)과 꺾임(하향)을 **대칭으로** 함께 담는다."""

    crossed_up: bool
    below_band: bool
    crossed_down: bool
    above_band: bool


class IndexMinuteCandles:
    """지수 1분봉 모음 — 시간 오름차순으로 정규화해 보관한다."""

    def __init__(self, candles: list[IndexMinuteCandle]) -> None:
        self._ordered = sorted(candles, key=lambda c: c.minute)

    @staticmethod
    def from_ticks(ticks: list["IndexTick"]) -> "IndexMinuteCandles":
        """10초 틱을 분 단위로 묶어 1분봉으로 합성한다."""
        groups: dict[datetime, list[IndexTick]] = {}
        for t in ticks:
            groups.setdefault(t.at.replace(second=0, microsecond=0), []).append(t)
        candles = []
        for minute, group in groups.items():
            ordered = sorted(group, key=lambda t: t.at)
            candles.append(
                IndexMinuteCandle(
                    minute=minute,
                    open=ordered[0].value,
                    high=max(t.value for t in ordered),
                    low=min(t.value for t in ordered),
                    close=ordered[-1].value,
                    volume=sum(t.volume for t in ordered),
                )
            )
        return IndexMinuteCandles(candles)

    def candles(self) -> list[IndexMinuteCandle]:
        return list(self._ordered)

    def moving_average(
        self, interval_minutes: int, period: int, rearm_margin: float
    ) -> IndexMaSignal | None:
        """종목 돌림(`MinuteCandles.moving_average`)과 동일 규칙 — 진행 중인 마지막 봉은 뺀다."""
        bars = self._aggregate(interval_minutes)[:-1]
        if len(bars) < period + 1:
            return None
        latest, prev = bars[-1], bars[-2]
        ma_latest = sum(b.close for b in bars[-period:]) / period
        ma_prev = sum(b.close for b in bars[-period - 1 : -1]) / period
        return IndexMaSignal(
            crossed_up=prev.close <= ma_prev and latest.close > ma_latest,
            below_band=latest.close < ma_latest * (1 - rearm_margin),
            crossed_down=prev.close >= ma_prev and latest.close < ma_latest,
            above_band=latest.close > ma_latest * (1 + rearm_margin),
        )

    def _aggregate(self, interval_minutes: int) -> list[IndexMinuteCandle]:
        """구간 경계로 묶어 종가=끝봉 종가로 합성(반등 판정은 종가만 쓴다)."""
        groups: dict[datetime, list[IndexMinuteCandle]] = {}
        for c in self._ordered:
            start = c.minute.replace(
                minute=c.minute.minute // interval_minutes * interval_minutes,
                second=0, microsecond=0,
            )
            groups.setdefault(start, []).append(c)
        return [group[-1] for _, group in sorted(groups.items())]
