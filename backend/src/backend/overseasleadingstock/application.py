"""해외 주도주 — 랭킹 / 상세 / 분봉 / 일봉.

미국 3개 거래소(나스닥·뉴욕·아멕스)를 합쳐 거래대금 상위를 뽑는다.
국내와 같은 흐름 — 거래대금 상위 풀에서 당일 등락률이 기준 이상인 것만 남긴다.
"""

import logging
from datetime import timedelta

from backend.library.cache import ttl_cache
from backend.library.time import KST
from backend.market import calendar
from backend.overseasleadingstock import minute_archive, minutes
from backend.overseasleadingstock.domain import (
    FilterResult,
    OverseasStockRank,
    OverseasStockRanks,
    regular_session_open,
)
from backend.platform.kis import overseas_chart, overseas_product, overseas_ranking

log = logging.getLogger(__name__)

EXCHANGES = ("NAS", "NYS", "AMS")
TOP_N = 40
MIN_CHANGE_RATE_PCT = 5.0          # 상세 B: 당일 등락률 하한
MIN_MARKET_CAP_USD = 2_000_000_000  # 상세 C: 시가총액 $2B 하한
MAX_OPENING_GAP_PCT = 7.0          # 상세 E: 전일 종가 대비 시가 상한 — 국내와 같다
#: 장중 수명. 갱신 폴러(10초)가 한 번 늦어도 비지 않게 여유를 둔다.
_POOL_TTL_SECONDS = 15


def _pool_ttl() -> float:
    """장중엔 짧게, 장이 멈춘 동안(장 밖·미국 휴장)은 다음 미 동부 04:00까지 들고 있는다.

    국내(`leadingstock.application._pool_ttl`)와 같은 규칙이다. 멈춘 동안엔 값이 안 바뀐다.
    """
    holiday, trading_hours = calendar.us_market_status()
    if not holiday and trading_hours:
        return _POOL_TTL_SECONDS
    return calendar.seconds_until_us_session()


@ttl_cache("overseasRankingPool", ttl_seconds=_pool_ttl, maxsize=1)
def _ranking_pool() -> list[OverseasStockRank]:
    """세 거래소를 합쳐 순위를 매기고, 상위 컷 안에서 ETF를 걷어낸다.

    랭킹(전시)과 상세 평가가 공유하는 후보 풀이다.

    **순위는 ETF를 포함한 통합 순위다.** KIS는 순위를 거래소별로 주기 때문에(나스닥 1위,
    뉴욕 1위, 아멕스 1위가 따로 온다) 합친 뒤 다시 매길 수밖에 없는데, ETF를 뺀 다음에
    매기면 "통합 12위"라고 적힌 종목이 실제로는 20위인 일이 생긴다. 국내는 키움이 주는
    원본 순위(ETF 포함)를 그대로 쓰므로, 자르기 전에 매겨 두 화면의 순위가 같은 뜻이 되게 한다.
    """
    rows = [
        _to_rank(item)
        for excd in EXCHANGES
        for item in overseas_ranking.fetch_trading_value_ranking(excd)
    ]
    rows.sort(key=lambda r: r.trading_value, reverse=True)
    ranked = [r.ranked(i + 1) for i, r in enumerate(rows)]
    return [r for r in ranked[:TOP_N] if not r.is_etf]


def refresh_ranking_pool() -> None:
    """후보 풀을 만료 전에 새로 받아 갈아 끼운다 — 장중 갱신 폴러가 부른다."""
    _ranking_pool.refresh()


def get_candidates(min_change_rate: float) -> list[OverseasStockRank]:
    """거래대금 상위 풀에서 등락률 기준을 통과한 것만. 국내와 같이 순위 예외를 두지 않는다.

    순위는 풀에서 받은 통합 순위를 그대로 둔다 — 걸러낸 뒤 다시 매기면 그 숫자가
    "몇 위인가"가 아니라 "이 목록의 몇 번째인가"가 된다. 목록의 번호는 화면이 매긴다.
    """
    return [r for r in _ranking_pool() if r.rate >= min_change_rate]


def get_leaders(count: int) -> list[OverseasStockRank]:
    """첫 화면용 — 거래대금·등락률이 함께 높은 상위 `count`개.

    랭킹과 달리 **등락률 기준을 받지 않는다.** 첫 화면은 보는 사람이 랭킹 화면에
    걸어둔 기준과 무관하게 같은 답을 보여야 한다.
    """
    return OverseasStockRanks(_ranking_pool()).leaders(count)


def evaluate_stock(exchange: str, symbol: str) -> dict:
    """종목 상세 — 필터 A(거래대금순위)·B(당일등락률)·C(시가총액)·D(시가 대비 현재가)·E(시초가) 평가.

    A·B는 후보 풀에서, C는 상품기본정보(상장주식수×현재가)로 산출한다.
    D·E의 시가는 차트가 받아 두는 1분봉 저장소에서 읽는다 — 새 요청을 늘리지 않는다.
    """
    stock = next(
        (r for r in _ranking_pool() if r.exchange == exchange and r.symbol == symbol),
        None,
    )
    if stock is None:
        raise LookupError(f"후보에 없는 종목: {exchange}:{symbol}")

    market_cap = overseas_product.fetch_market_cap(exchange, symbol)
    session_open = _session_open(exchange, symbol)
    from_open = (stock.price - session_open) / session_open * 100 if session_open else None
    prev_close = stock.previous_close
    gap = (session_open - prev_close) / prev_close * 100 if session_open and prev_close > 0 else None

    filters = [
        FilterResult(
            filter_name="거래대금순위",
            criteria_description=f"통합 상위 {TOP_N}위 이내",
            actual_value=f"{stock.rank}위",
            passed=stock.rank <= TOP_N,
            value=stock.rank,
            threshold=TOP_N,
        ),
        FilterResult(
            filter_name="당일 등락률",
            criteria_description=f"{int(MIN_CHANGE_RATE_PCT)}% 이상",
            actual_value=f"{stock.rate:+.2f}%",
            passed=stock.rate >= MIN_CHANGE_RATE_PCT,
            value=stock.rate,
            threshold=MIN_CHANGE_RATE_PCT,
        ),
        FilterResult(
            filter_name="시가총액",
            criteria_description=f"${MIN_MARKET_CAP_USD // 1_000_000_000}B 이상",
            actual_value=_format_usd_cap(market_cap) if market_cap is not None else "조회 불가",
            passed=market_cap is not None and market_cap >= MIN_MARKET_CAP_USD,
        ),
        FilterResult(
            filter_name="시가 대비 현재가",
            criteria_description="현재가 ≥ 시가",
            actual_value=f"{from_open:+.2f}%" if from_open is not None else "시가 없음",
            passed=from_open is not None and from_open >= 0,
            value=from_open,
            threshold=0.0,
        ),
        FilterResult(
            filter_name="시초가",
            criteria_description=f"시초가 {MAX_OPENING_GAP_PCT}% 이하",
            actual_value=f"${session_open:,.2f} ({gap:+.2f}%)" if gap is not None else "시초가 없음",
            passed=gap is not None and gap <= MAX_OPENING_GAP_PCT,
            value=gap,
            threshold=MAX_OPENING_GAP_PCT,
        ),
    ]

    return {
        "stock": stock,
        "market_cap": market_cap,
        "filters": filters,
    }


def _session_open(exchange: str, symbol: str) -> float | None:
    """최신 거래일의 정규장 시가. 분봉을 못 받으면 None — 상세의 나머지 조건은 그대로 보여 준다."""
    try:
        bars = minutes.minute_candles(exchange, symbol)
    except Exception:
        log.warning("해외 분봉을 못 받아 시가 조건을 비운다 (%s:%s)", exchange, symbol, exc_info=True)
        return None
    if not bars:
        return None
    day = max(b.trading_day for b in bars)
    zone = calendar.Region.US.zone
    return regular_session_open([
        (b.date_time.replace(tzinfo=KST).astimezone(zone).replace(tzinfo=None), b.open)
        for b in bars if b.trading_day == day
    ])


def minute_candles(exchange: str, symbol: str) -> list[overseas_chart.OverseasMinuteCandle]:
    """종목 1분봉 (한국 시각순 오름차순). 종목별로 들고 있다가 새 봉만 이어 받는다."""
    return sorted(minutes.minute_candles(exchange, symbol), key=lambda c: c.date_time)


def daily_candles(exchange: str, symbol: str) -> list[overseas_chart.OverseasDailyCandle]:
    """종목 일봉 (일자 오름차순)."""
    return sorted(overseas_chart.fetch_daily_candles(exchange, symbol), key=lambda c: c.date)


def _format_usd_cap(usd: int) -> str:
    """1조 이상은 $X.XXT, 그 외는 $X,XXXB."""
    if usd >= 1_000_000_000_000:
        return f"${usd / 1_000_000_000_000:.2f}T"
    return f"${usd // 1_000_000_000:,}B"


def _to_rank(item: overseas_ranking.OverseasRankItem) -> OverseasStockRank:
    """rate(등락율)는 이미 부호 포함. diff(대비)는 절댓값이라 sign으로 방향을 부여한다."""
    negative = item.sign.strip() in ("4", "5")  # 4:하한가 5:하락
    diff_sign = -1.0 if negative else 1.0
    return OverseasStockRank(
        rank=0,  # 통합 정렬 후 재부여
        exchange=item.excd.strip(),
        symbol=item.symb.strip(),
        name=item.name.strip(),
        ename=item.ename.strip(),
        price=_to_float(item.last),
        diff=diff_sign * _to_float(item.diff),
        rate=_to_float(item.rate),
        trading_value=_to_float(item.tamt),
    )


def _to_float(value: str) -> float:
    try:
        return float(value.strip())
    except (TypeError, ValueError):
        return 0.0


#: 지난 날 분봉 보관 기간 — 국내와 같다. 수정주가(액면분할 등) 소급이 반영되지 않는 봉을 오래 두지 않는다.
_MINUTE_ARCHIVE_RETENTION = timedelta(days=14)


def settle_minutes() -> int:
    """마감 뒤 들고 있는 종목의 오늘 봉을 굳혀 보관소에 넘기고, 2주 지난 날을 지운다. 실패한 종목 수를 돌려준다."""
    failures = minutes.settle()
    try:
        minute_archive.purge(calendar.Region.US.today() - _MINUTE_ARCHIVE_RETENTION)
    except Exception:
        log.warning("해외 지난 날 분봉 정리 실패", exc_info=True)
    return failures
