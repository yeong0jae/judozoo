"""주도주 도메인 값 객체.

M5 본체(필터·시그널)는 아직이다. **platform 어댑터가 반환하는 타입만** 선행해서 둔다 —
Kotlin도 `KiwoomMarketClient`·`KiwoomIndexClient`가 이 타입들을 그대로 돌려준다.
아키텍처 규칙상 상위 피처가 발행한 값 객체를 하위가 쓰는 것은 허용된다(행위 결합이 아니라 데이터 교환).
"""

from dataclasses import dataclass
from datetime import date, datetime

from backend.library.ranking import top_balanced

#: 일반 종목의 가격제한폭(%). 이보다 크게 움직였다면 제한폭이 넓은 날이다 — 상장 첫날이 대표적이다
NORMAL_LIMIT_RATE = 30.0
#: 제한폭이 넓은 날(상장 첫날) 순위용 등락률을 이만큼 나눈다. 처음엔 제한폭 대비(+30% / +300% = ÷10)로
#: 환산했는데, 점수가 등수를 쓰니 그래도 위에 남았다(2026-10-01 12:43 브릴스 +68.97% → 6.90%, 17종목 중
#: 등락률 3번째, 주도주 3위). 20이면 3.45% → 5번째, 3위 → 4위.
LISTING_RATE_DIVISOR = 20.0
#: 제한폭이 넓은 날(상장 첫날) 순위용 거래대금을 이만큼 나눈다. 상장 첫날은 손바뀜이 몰려 거래대금이
#: 부풀려진다 — 등락률을 환산해도 거래대금 축이 높아 1위를 지켰다(2026-10-01 브릴스: 등락률을 ÷40은 해야
#: 1위가 바뀌었다). 2면 그날 브릴스 2,546억 → 1,273억, 1위 → 4위. 돈이 몰린 것 자체는 사실이라 크게 깎지
#: 않는다 — ÷3이면 같은 날 17종목 중 16위로 떨어졌다(백분위라 1,100억~1,300억대 무리 아래로 빠진다).
LISTING_TRADING_VALUE_DIVISOR = 2.0


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
    accumulated_volume: int | None = None
    market_cap: int = 0
    opening_price: int = 0
    previous_close: int = 0
    high_price: int = 0
    low_price: int = 0
    program_net_buy: int = 0
    #: 상한가로 잠겼는가. **거래소가 준 판정**이라 등락률로 대신 가릴 수 없다 —
    #: 신규상장 종목은 제한폭이 없어 +150%로도 상한가가 아니다.
    limit_up: bool = False
    #: 오늘이 제한폭이 넓은 날(상장 첫날)인가. 오늘 한 번이라도 +30%를 넘었으면 **그날 내내** 참이다 —
    #: 채우는 쪽은 `leadingstock.wide_limit_days`다. 지금 등락률만 보면 +100%에서 +26%로 밀려 내려온
    #: 신규상장 종목이 갑자기 "+26% 급등주"가 되어 순위가 튄다.
    wide_limit_day: bool = False

    @property
    def exceeds_normal_limit(self) -> bool:
        """일반 종목은 닿을 수 없는 폭(+30% 초과)으로 올랐는가 — 제한폭이 넓은 날이라는 표시다."""
        return self.price_change_rate > NORMAL_LIMIT_RATE

    @property
    def is_wide_limit_day(self) -> bool:
        """제한폭이 넓은 날(상장 첫날)인가 — 오늘 넘은 적이 있거나 지금 넘어 있다."""
        return self.wide_limit_day or self.exceeds_normal_limit

    @property
    def trading_value_for_ranking(self) -> float:
        """주도주 순위를 매길 때 쓰는 거래대금. 제한폭이 넓은 날이면 부풀려진 만큼 나눈다.

        화면에는 원래 거래대금을 보인다 — 여기 값은 순위에만 쓴다.
        """
        if not self.is_wide_limit_day:
            return self.accumulated_trading_value
        return self.accumulated_trading_value / LISTING_TRADING_VALUE_DIVISOR

    @property
    def rate_for_ranking(self) -> float:
        """주도주 순위를 매길 때 쓰는 등락률. 제한폭이 넓은 날이면 `LISTING_RATE_DIVISOR`로 나눈다.

        상장 첫날 등락률은 공모가 대비라 일반 종목의 하루 등락률과 한 줄에 세울 수 없다. 점수가
        등락률의 **등수**를 쓰므로 조금 깎아서는 소용없다 — +30%를 넘는 한 늘 1등이다. 그래서
        일반 종목의 하루 폭 안으로 끌어내린다: 공모가 대비 +86.9%는 +4.35%로 견준다.
        화면에는 원래 등락률을 보인다 — 여기 값은 순위에만 쓴다.

        지금 +30% 아래로 내려와 있어도 그날 넘은 적이 있으면(`wide_limit_day`) 계속 나눈다.
        """
        if not self.is_wide_limit_day:
            return self.price_change_rate
        return self.price_change_rate / LISTING_RATE_DIVISOR


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

from enum import Enum  # noqa: E402


class SpikeDirection(Enum):
    """스파이크 봉의 방향 — 차트 거래량 막대 색과 같다. 종가 ≥ 시가(빨강)면 매수, 아니면(파랑) 매도.

    FLAT은 더 만들지 않는다. 예전에 종가의 고저 위치로 판단하던 때 쌓인 기록을 읽으려고 남겨 둔다.
    """

    BUY = "BUY"
    SELL = "SELL"
    FLAT = "FLAT"


@dataclass(frozen=True)
class SwingHighSignal:
    """전고점(받은 분봉 구간의 최고가) 돌파 매매 시그널.

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
    """지지선(받은 분봉 구간의 최저가) 근접 시그널. `SwingHighSignal`의 대칭.

    `gap_rate` = (현재가 − 저가) / 현재가 × 100 — 저가까지 남은 하락률(%).
    이미 닿았거나 깨고 내려갔으면 0 이하.
    """

    trough_price: int
    trough_at: datetime
    gap_rate: float


#: 주도주 점수에서 거래대금 축에 주는 무게(나머지 0.3은 등락률). 0.5면 두 축이 대등하다.
#:
#: **거래대금 쪽으로 기울여 둔 값이다.** 등락률이 두 자릿수인 중소형주보다 돈이 실제로
#: 몰린 종목을 위에 세운다. 0.45(등락률 우위) → 0.7 → 0.65로 옮겨온 값이다.
#:
#: 0.45→0.7: 2026-09-18 장중 후보 19종목 기준 19개 중 17개의 자리가 바뀌었다 —
#: 등락률만 높은 종목이 밀리고 거래대금 상위 대형주가 올라온다.
#:
#: 0.7→0.65: 반대로 **크게 오른 종목이 너무 쉽게 밀렸다.** 2026-09-19 00:26 해외 후보
#: 16종목 기준, +12.34%로 등락률 1위인 종목이 0.7에서는 5위였고 0.65에서 4위가 된다 —
#: 거래대금 백분위 0.5짜리가 0.8짜리를 넘어서는 경계가 0.669다.
#:
#: 아래로 0.5까지는 두 축이 대등해지고, 0.4 아래로 내려가면 시총 1천억대 상한가 종목이
#: 대장주를 밀어내기 시작한다.
#: 해외(`overseasleadingstock`)는 **따로 간다(0.55).** 두 카드가 나란히 서지만 풀의
#: 성질이 다르다 — 거기 거래대금 편차가 훨씬 커서 같은 값으로는 등락률이 묻힌다.
TRADING_VALUE_WEIGHT = 0.65


class LeadingStocks:
    """거래대금 상위 풀 — 그 안에서 "주도주다움"으로 다시 세운다."""

    def __init__(self, stocks: list[LeadingStockSnapshot]) -> None:
        self._stocks = stocks

    def leaders(self, count: int) -> list[LeadingStockSnapshot]:
        """거래대금·등락률 두 축이 모두 높은 순으로 `count`개. 상장 첫날은 두 축 모두 보정한 값을 쓴다
        (`trading_value_for_ranking`, `rate_for_ranking`).

        **오른 종목만 본다.** 돈이 아무리 붙어도 내린 종목은 그날의 주도주가 아니다.
        """
        risen = [s for s in self._stocks if s.price_change_rate > 0]
        return top_balanced(
            risen,
            lambda s: s.trading_value_for_ranking,
            lambda s: s.rate_for_ranking,
            count,
            TRADING_VALUE_WEIGHT,
        )

    def limit_ups(self) -> list[LeadingStockSnapshot]:
        """이 풀 안에서 상한가인 종목 — 받은 순서(거래대금 내림차순) 그대로.

        **점수로 다시 세우지 않는다.** 상한가는 등수를 다투는 값이 아니라 "지금 잠겼다"는
        상태라, 몇 종목이 어느 종목인지만 뜻이 있다.
        """
        return [s for s in self._stocks if s.limit_up]


class MinuteCandles:
    """분봉 모음 — 시간 오름차순으로 정규화해 보관한다.

    구간은 넣는 쪽이 정한다 — 저항·지지는 최근 3거래일, 스파이크는 당일만 넘긴다.
    """

    def __init__(self, candles: list[MinuteCandle]) -> None:
        self._ordered = sorted(candles, key=lambda c: c.date_time)

    def peak_signal(self, current_price: int) -> SwingHighSignal | None:
        """받은 분봉 중 최고가를 전고점(돌파 대상 저항)으로 본다.

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

    def trough_signal(self, current_price: int) -> SwingLowSignal | None:
        """받은 분봉 중 최저가를 지지선으로 본다. `peak_signal`과 같은 방식이다.

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


def _spike_direction(candle: MinuteCandle) -> SpikeDirection:
    # 보합(종가 = 시가)도 차트에선 빨강이다 — 화면에서 보는 색과 어긋나지 않게 매수로 둔다
    return SpikeDirection.BUY if candle.close_price >= candle.open_price else SpikeDirection.SELL


class DailyCandles:
    """일봉 모음 — 입력은 **최신순(내림차순)**을 가정한다."""

    def __init__(self, candles: list[DailyCandle]) -> None:
        self._candles = candles

    def relative_volume(self, today: date, lookback: int) -> float | None:
        """풀데이 상대거래량(RVOL) = 당일 누적 거래량 / 직전 N거래일 평균.

        장 초반엔 당일 누적이 아직 적어 1 미만으로 낮게 나온다(풀데이 방식의 한계).
        """
        today_candle = next((c for c in self._candles if c.date == today), None)
        return self.relative_volume_from(
            today_candle.volume if today_candle is not None else None, today, lookback,
        )

    def relative_volume_from(self, current_volume: int | None, today: date, lookback: int) -> float | None:
        """현재 누적 거래량이 별도 시세에서 올 때 과거 일봉만으로 RVOL을 계산한다."""
        if current_volume is None:
            return None
        baseline = [c.volume for c in self._candles if c.date < today][:lookback]
        if not baseline:
            return None
        avg = sum(baseline) / len(baseline)
        if avg <= 0:
            return None
        return current_volume / avg


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
        """진행 중인 마지막 봉은 뺀다."""
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
