"""주도주 후보 필터 — 전부 순수 함수다.

Spring·시간·HTTP에 의존하지 않는다. 일봉·분봉·프로그램순매수·테마순위처럼 바깥 데이터가
필요한 필터는 **provider 함수를 주입받아** 테스트에서 그대로 갈아끼울 수 있게 한다.

`evaluate()`가 만드는 문자열은 화면에 그대로 나가므로 Kotlin과 **글자까지 같아야** 한다.
"""

import logging
from abc import ABC, abstractmethod
from collections.abc import Callable
from dataclasses import dataclass
from datetime import date

from backend.leadingstock.domain import DailyCandle, LeadingStockSnapshot, MinuteCandle
from backend.library.money import format_korean_money, format_korean_money_with_man, format_price
from backend.settings import LeadingStockCriteria

log = logging.getLogger(__name__)

DailyCandleProvider = Callable[[str], list[DailyCandle]]
MinuteCandleProvider = Callable[[str], list[MinuteCandle]]


@dataclass(frozen=True)
class FilterEvaluationResult:
    filter_name: str
    criteria_description: str
    actual_value: str
    passed: bool
    #: 실측값·기준값을 숫자로 — 화면이 눈금 막대를 그린다. 막대가 없는 조건이나 값이 없으면 None
    value: float | None = None
    threshold: float | None = None


class StockFilter(ABC):
    name: str

    @abstractmethod
    def filter(self, stock: LeadingStockSnapshot) -> bool: ...

    @abstractmethod
    def evaluate(self, stock: LeadingStockSnapshot) -> FilterEvaluationResult: ...

    def _result(
        self, stock: LeadingStockSnapshot, description: str, actual: str,
        value: float | None = None, threshold: float | None = None,
    ) -> FilterEvaluationResult:
        return FilterEvaluationResult(self.name, description, actual, self.filter(stock), value, threshold)


class FilterChain:
    def __init__(self, filters: list[StockFilter]) -> None:
        self._filters = filters

    def apply(self, stocks: list[LeadingStockSnapshot]) -> list[LeadingStockSnapshot]:
        remaining = stocks
        for f in self._filters:
            before = len(remaining)
            remaining = [s for s in remaining if f.filter(s)]
            log.debug("[%s] %d -> %d stocks", f.name, before, len(remaining))
            if not remaining:
                break
        return remaining


class TradingValueRankFilter(StockFilter):
    name = "거래대금순위"

    def __init__(self, criteria: LeadingStockCriteria) -> None:
        self._criteria = criteria

    def filter(self, stock):
        return stock.trading_value_rank <= self._criteria.max_trading_value_rank

    def evaluate(self, stock):
        return self._result(
            stock,
            f"상위 {self._criteria.max_trading_value_rank}위 이내",
            f"{stock.trading_value_rank}위",
            stock.trading_value_rank, self._criteria.max_trading_value_rank,
        )


class DailyPriceChangeFilter(StockFilter):
    name = "당일등락률"

    def __init__(self, criteria: LeadingStockCriteria) -> None:
        self._criteria = criteria

    def filter(self, stock):
        return stock.price_change_rate >= self._criteria.min_daily_price_change_rate

    def evaluate(self, stock):
        return self._result(
            stock,
            f"{self._criteria.min_daily_price_change_rate}% 이상",
            f"{stock.price_change_rate:+.2f}%",
            stock.price_change_rate, self._criteria.min_daily_price_change_rate,
        )


class MarketCapFilter(StockFilter):
    name = "시가총액"

    def __init__(self, criteria: LeadingStockCriteria) -> None:
        self._criteria = criteria

    def filter(self, stock):
        return stock.market_cap >= self._criteria.min_market_cap

    def evaluate(self, stock):
        # 둘 다 억원 단위 → 원 단위로 환산해 표시
        return self._result(
            stock,
            f"{format_korean_money(self._criteria.min_market_cap * 100_000_000)} 이상",
            format_korean_money(stock.market_cap * 100_000_000),
        )


class PriceAboveOpenFilter(StockFilter):
    name = "시가 대비 현재가"

    def filter(self, stock):
        return stock.opening_price > 0 and stock.current_price >= stock.opening_price

    def evaluate(self, stock):
        rate = None
        if stock.opening_price > 0:
            rate = (stock.current_price - stock.opening_price) / stock.opening_price * 100
            actual = f"{rate:+.2f}%"
        else:
            actual = "시가 없음"
        return self._result(stock, "현재가 ≥ 시가", actual, rate, 0.0)


class PrevDayCloseFilter(StockFilter):
    name = "전일 등락률"

    def __init__(self, criteria: LeadingStockCriteria, daily_candles: DailyCandleProvider) -> None:
        self._criteria = criteria
        self._daily_candles = daily_candles

    def filter(self, stock):
        candles = self._daily_candles(stock.stock_code)
        if len(candles) < 2:
            return False
        # candles[0] = 오늘, candles[1] = 어제
        return candles[1].change_rate <= self._criteria.max_prev_close_change_rate

    def evaluate(self, stock):
        candles = self._daily_candles(stock.stock_code)
        rate = candles[1].change_rate if len(candles) >= 2 else None
        actual = f"{rate:+.2f}%" if rate is not None else "데이터 부족"
        return self._result(
            stock, f"{self._criteria.max_prev_close_change_rate}% 이하", actual,
            rate, self._criteria.max_prev_close_change_rate,
        )


class OpeningPriceFilter(StockFilter):
    name = "시초가"

    def __init__(self, criteria: LeadingStockCriteria, daily_candles: DailyCandleProvider) -> None:
        self._criteria = criteria
        self._daily_candles = daily_candles

    def _open_change_rate(self, stock) -> float | None:
        candles = self._daily_candles(stock.stock_code)
        if len(candles) < 2:
            return None
        today, yesterday = candles[0], candles[1]
        if today.open_price <= 0 or yesterday.close_price <= 0:
            return None
        return (today.open_price - yesterday.close_price) / yesterday.close_price * 100

    def filter(self, stock):
        rate = self._open_change_rate(stock)
        return rate is not None and rate <= self._criteria.max_opening_price_change_rate

    def evaluate(self, stock):
        candles = self._daily_candles(stock.stock_code)
        rate = self._open_change_rate(stock)
        if len(candles) < 2:
            actual = "데이터 부족"
        else:
            actual = (
                f"{format_price(candles[0].open_price)} ({rate:+.2f}%)"
                if rate is not None
                else "시초가 없음"
            )
        return self._result(
            stock, f"시초가 {self._criteria.max_opening_price_change_rate}% 이하", actual,
            rate, self._criteria.max_opening_price_change_rate,
        )


class EtfExclusionFilter(StockFilter):
    """키움 응답에 종목 유형 필드가 없어 **종목명 prefix**(운용사 브랜드)로 거른다."""

    name = "ETF/ETN 제외"

    _BRAND_PREFIXES = (
        "KODEX ", "TIGER ", "KOSEF ", "KBSTAR ", "ARIRANG ",
        "ACE ", "HANARO ", "SOL ", "RISE ", "WOORI ",
        "KOACT ", "히어로즈 ", "PLUS ", "MASTER ",
    )

    def filter(self, stock):
        return not self._is_etf_or_etn(stock.stock_name)

    def evaluate(self, stock):
        passed = self.filter(stock)
        return self._result(stock, "개별 종목만 (ETF/ETN 제외)", "개별 종목" if passed else "ETF/ETN")

    def _is_etf_or_etn(self, name: str) -> bool:
        if any(name.startswith(p) for p in self._BRAND_PREFIXES):
            return True
        # 뒤에 ETN이 붙는 케이스 ("한투 ETN 코스피200 H" 등)
        return " ETN" in name


class SpacExclusionFilter(StockFilter):
    """스팩(기업인수목적회사) 제외 — 종목명에 "스팩"이 들어가는지로 거른다."""

    name = "스팩 제외"
    _SPAC_KEYWORD = "스팩"

    def filter(self, stock):
        return self._SPAC_KEYWORD not in stock.stock_name

    def evaluate(self, stock):
        passed = self.filter(stock)
        return self._result(stock, "개별 종목만 (스팩 제외)", "개별 종목" if passed else "스팩")


class DailyHighPositionFilter(StockFilter):
    name = "최근 고가 대비 현재가"

    def __init__(self, criteria: LeadingStockCriteria, daily_candles: DailyCandleProvider, as_of: date) -> None:
        self._criteria = criteria
        self._daily_candles = daily_candles
        self._as_of = as_of

    def _drop_rate(self, stock) -> float | None:
        candles = sorted(
            (c for c in self._daily_candles(stock.stock_code) if c.date < self._as_of),
            key=lambda c: c.date,
            reverse=True,
        )[:60]
        if not candles:
            return None
        max_high = max(c.high_price for c in candles)
        if max_high <= 0:
            return None
        return (stock.current_price - max_high) / max_high * 100

    def filter(self, stock):
        rate = self._drop_rate(stock)
        return rate is not None and rate >= self._criteria.max_high_position_drop_rate

    def evaluate(self, stock):
        rate = self._drop_rate(stock)
        actual = f"{rate:.2f}%" if rate is not None else "데이터 없음"
        return self._result(
            stock,
            f"전일까지 최근 60거래일 고가 대비 현재가 {self._criteria.max_high_position_drop_rate}% 이상",
            actual,
            rate, self._criteria.max_high_position_drop_rate,
        )


class MinuteCandleVolumeFilter(StockFilter):
    name = "1분봉거래대금"

    def __init__(self, criteria: LeadingStockCriteria, minute_candles: MinuteCandleProvider) -> None:
        self._criteria = criteria
        self._minute_candles = minute_candles

    def filter(self, stock):
        candles = self._minute_candles(stock.stock_code)
        if not candles:
            return False
        latest = candles[0]
        if latest.trading_value < self._criteria.min_minute_trading_value:
            return False
        avg = sum(c.trading_value for c in candles) / len(candles)
        if avg <= 0:
            return False
        return latest.trading_value / avg * 100 >= self._criteria.min_minute_volume_increase_rate

    def evaluate(self, stock):
        candles = self._minute_candles(stock.stock_code)
        if candles:
            latest = candles[0]
            avg = sum(c.trading_value for c in candles) / len(candles)
            rate = latest.trading_value / avg * 100 if avg > 0 else 0.0
            actual = f"{format_korean_money(latest.trading_value)} ({rate:.0f}%)"
        else:
            actual = "데이터 없음"
        return self._result(
            stock,
            f"{format_korean_money(self._criteria.min_minute_trading_value)} 이상 "
            f"& 증가율 {int(self._criteria.min_minute_volume_increase_rate)}%",
            actual,
        )


class MinuteCandleFluctuationFilter(StockFilter):
    name = "1분봉등락률"

    def __init__(self, criteria: LeadingStockCriteria, minute_candles: MinuteCandleProvider) -> None:
        self._criteria = criteria
        self._minute_candles = minute_candles

    def _rate(self, stock) -> float | None:
        candles = self._minute_candles(stock.stock_code)
        if not candles:
            return None
        latest = candles[0]
        if latest.open_price <= 0:
            return None
        return abs((latest.close_price - latest.open_price) / latest.open_price * 100)

    def filter(self, stock):
        rate = self._rate(stock)
        return rate is not None and rate <= self._criteria.max_minute_fluctuation_rate

    def evaluate(self, stock):
        rate = self._rate(stock)
        actual = f"{rate:.2f}%" if rate is not None else "데이터 없음"
        return self._result(stock, f"{self._criteria.max_minute_fluctuation_rate}% 이하", actual)


class ProgramNetBuyFilter(StockFilter):
    name = "프로그램 양매수"

    def __init__(self, criteria: LeadingStockCriteria, program_net_buy: Callable[[str], int]) -> None:
        self._criteria = criteria
        self._program_net_buy = program_net_buy

    def filter(self, stock):
        return self._program_net_buy(stock.stock_code) >= self._criteria.min_program_net_buy

    def evaluate(self, stock):
        net_buy = self._program_net_buy(stock.stock_code)
        # 둘 다 백만원 단위 → 원 단위로 환산해 표시
        return self._result(
            stock,
            f"{format_korean_money(self._criteria.min_program_net_buy * 1_000_000)} 이상",
            format_korean_money_with_man(net_buy * 1_000_000),
        )
