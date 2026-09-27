"""키움 시세 — 거래대금/등락률 상위, 종목 기본정보, 일봉·분봉.

**함정 두 가지가 여기 모여 있다.**

1. `cur_prc` 등 가격 필드의 앞 부호는 등락 **방향 표식**이지 음수가 아니다. 절대값으로 읽는다.
2. KRX 기본 코드는 정규장만 준다. 프리·애프터마켓이 필요한 TR은 SOR 통합(`_AL`) 코드라야 한다.
   단 일봉(ka10081)은 `_AL`이면 빈 응답이 오는 경우가 있어 **기본 코드로 부른다**.
"""

import logging
from datetime import date, datetime

from backend.leadingstock.domain import DailyCandle, LeadingStockSnapshot, MinuteCandle
from backend.library.cache import is_empty, ttl_cache
from backend.library.time import today
from backend.platform.kiwoom import client

log = logging.getLogger(__name__)

_RANK_URL = "/api/dostk/rkinfo"
_STOCK_INFO_URL = "/api/dostk/stkinfo"
_CHART_URL = "/api/dostk/chart"

#: 키움 등락부호(`pred_pre_sig`) 중 상한가. 1:상한 2:상승 3:보합 4:하한 5:하락.
#:
#: **등락률로 상한가를 가려내면 안 된다.** 신규상장 종목은 제한폭이 없어 부호가 2(상승)인 채로
#: +150%가 나온다 — 2026-09-22 실측에서 한국제17호스팩이 +153.00%/부호 2였다.
#: 거래소가 직접 주는 이 값만 상한가를 정확히 가른다.
_SIG_LIMIT_UP = "1"


def parse_price(value: str) -> int:
    """키움 가격 — 앞의 +/- 는 등락 방향 표식이라 떼고 절대값으로 읽는다."""
    trimmed = (value or "").strip()
    if not trimmed:
        return 0
    cleaned = trimmed.removeprefix("+").removeprefix("-").replace("-", "")
    try:
        return int(cleaned)
    except ValueError:
        return 0


def _with_sor_suffix(stock_code: str) -> str:
    """접미사가 없는 코드에 SOR 통합(`_AL`)을 붙인다. 이미 `_AL`/`_NX`면 그대로 둔다."""
    return stock_code if "_" in stock_code else f"{stock_code}_AL"


def _is_invalid_token(message: str | None) -> bool:
    return bool(message) and ("8005" in message or "Token이 유효하지 않" in message)


def _with_token_retry(call):
    """토큰 무효(8005)면 캐시를 버리고 1회 재시도.

    같은 앱키를 다른 프로세스가 공유할 때 저쪽이 새 토큰을 받으면 이쪽 토큰이 즉시 죽는다.
    """
    try:
        return call()
    except RuntimeError as e:
        if not _is_invalid_token(str(e)):
            raise
        log.warning("Kiwoom 토큰 무효(8005) 감지 — 캐시 무효화 후 1회 재시도")
        client.invalidate()
        return call()


def fetch_top_trading_value_stocks(count: int = 50) -> list[LeadingStockSnapshot]:
    """거래대금 상위 (ka10032).

    **캐시하지 않는다.** 언제까지 들고 있을지가 장 상태(휴장 포함)에 달려 있어
    `leadingstock.application`이 캐시를 쥔다.
    빈 응답·오류는 예외로 올린다. 빈 결과를 캐싱하면 후속 폴링이 TTL 동안 빈 목록을 돌려준다.
    """
    return _with_token_retry(lambda: _fetch_top_trading_value_once(count))


def _fetch_top_trading_value_once(count: int) -> list[LeadingStockSnapshot]:
    log.info("키움 거래대금 상위 %d건 조회", count)
    response = client.get_client().post(
        _RANK_URL,
        headers=client.query_headers("ka10032"),
        json={
            "mrkt_tp": "000",       # 000:전체
            "mang_stk_incls": "0",  # 0:관리종목 미포함
            "stex_tp": "3",         # 3:KRX+NXT 통합
        },
    )
    response.raise_for_status()
    body = response.json()

    code = body.get("return_code")
    if code is not None and code != 0:
        raise RuntimeError(
            f"Kiwoom trading value ranking error. code={code} msg={body.get('return_msg')}"
        )
    items = body.get("trde_prica_upper")
    if items is None:
        raise RuntimeError(
            f"Kiwoom trading value ranking returned no list. msg={body.get('return_msg')}"
        )

    snapshots = []
    for index, item in enumerate(items[:count]):
        # trde_prica는 백만원 단위
        million = _to_int(item.get("trde_prica"))
        snapshots.append(
            LeadingStockSnapshot(
                stock_code=item.get("stk_cd", ""),
                stock_name=item.get("stk_nm", ""),
                current_price=parse_price(item.get("cur_prc", "")),
                price_change_rate=_to_float(item.get("flu_rt")),
                trading_value_rank=_to_int(item.get("now_rank")) or (index + 1),
                accumulated_trading_value=million * 1_000_000,
                limit_up=item.get("pred_pre_sig") == _SIG_LIMIT_UP,
            )
        )
    return snapshots


def fetch_top_price_change_rate_stocks(count: int = 50) -> list[LeadingStockSnapshot]:
    """등락률 상위 (ka10027). 실패해도 빈 목록 — 보조 지표라 호출부를 막지 않는다."""
    try:
        log.info("키움 등락률 상위 %d건 조회", count)
        response = client.get_client().post(
            _RANK_URL,
            headers=client.query_headers("ka10027"),
            json={
                "mrkt_tp": "000",
                "sort_tp": "1",        # 1:상승률
                "trde_qty_cnd": "0000",
                "stk_cnd": "0",
                "crd_cnd": "0",
                "updown_incls": "0",   # 0:상하한 불포함
                "pric_cnd": "0",
                "trde_prica_cnd": "0",
                "stex_tp": "3",
            },
        )
        response.raise_for_status()
        items = response.json().get("pred_pre_flu_rt_upper") or []
        return [
            LeadingStockSnapshot(
                stock_code=item.get("stk_cd", ""),
                stock_name=item.get("stk_nm", ""),
                current_price=parse_price(item.get("cur_prc", "")),
                price_change_rate=_to_float(item.get("flu_rt")),
                trading_value_rank=0,
                accumulated_trading_value=0,
            )
            for item in items[:count]
        ]
    except Exception:
        log.error("키움 등락률 상위 조회 실패", exc_info=True)
        return []


@ttl_cache("stockDetail", ttl_seconds=5, maxsize=60, skip_if=lambda r: r is None)
def fetch_stock_detail(stock_code: str) -> LeadingStockSnapshot | None:
    """종목 기본 정보 (ka10001).

    KRX 기본 코드는 정규장 종가에 멈춘다 — SOR 통합(`_AL`)이라야 NXT 프리·애프터 체결이 반영된다.
    후보 코드는 랭킹에서 이미 접미사를 달고 오므로 없을 때만 붙인다.
    """
    try:
        log.info("키움 종목 기본정보 조회 stk_cd=%s", stock_code)
        response = client.get_client().post(
            _STOCK_INFO_URL,
            headers=client.query_headers("ka10001"),
            json={"stk_cd": _with_sor_suffix(stock_code)},
        )
        response.raise_for_status()
        body = response.json()
        return LeadingStockSnapshot(
            stock_code=body.get("stk_cd", ""),
            stock_name=body.get("stk_nm", ""),
            current_price=parse_price(body.get("cur_prc", "")),
            price_change_rate=_to_float(body.get("flu_rt")),
            trading_value_rank=0,
            accumulated_trading_value=0,
            market_cap=_to_int(body.get("mac")),
            opening_price=parse_price(body.get("open_pric", "")),
            previous_close=parse_price(body.get("base_pric", "")),
            high_price=parse_price(body.get("high_pric", "")),
            low_price=parse_price(body.get("low_pric", "")),
        )
    except Exception:
        log.error("키움 종목 기본정보 조회 실패 stk_cd=%s", stock_code, exc_info=True)
        return None


def fetch_daily_candles(stock_code: str, count: int = 60, base_date: date | None = None) -> list[DailyCandle]:
    """일봉 (ka10081) — base_dt 기준 과거 봉 N개."""
    return _fetch_daily_page(stock_code, base_date or today())[:count]


@ttl_cache("dailyCandles", ttl_seconds=30, maxsize=60, skip_if=is_empty)
def _fetch_daily_page(stock_code: str, base: date) -> list[DailyCandle]:
    """ka10081 한 페이지(600봉, 최신순)를 통째로.

    **캐시 키는 키움에 보내는 값(종목·기준일)뿐이다.** 개수는 보내지 않고 받은 뒤 자르므로,
    개수를 키에 넣으면 필터용(60)과 차트(200)가 같은 요청을 두 번 보낸다.
    ka10081은 `_AL`이면 빈 응답이 오는 경우가 있어 **KRX 기본 코드**로 부른다.
    """
    try:
        log.info("키움 일봉 조회 stk_cd=%s base=%s", stock_code, base)
        response = client.get_client().post(
            _CHART_URL,
            headers=client.query_headers("ka10081"),
            json={
                "stk_cd": stock_code,
                "base_dt": base.strftime("%Y%m%d"),
                "upd_stkpc_tp": "1",
            },
        )
        response.raise_for_status()
        items = response.json().get("stk_dt_pole_chart_qry") or []

        candles = []
        for item in items:
            # 응답 끝쪽에 빈 패딩 항목이 올 수 있다 — 파싱 실패는 건너뛴다.
            parsed = _parse_date(item.get("dt", ""))
            if parsed is None:
                continue
            close = parse_price(item.get("cur_prc", ""))
            # pred_pre는 부호 포함 정수(그날 종가 - 전일종가). 전일종가 기준으로 등락률을 만든다.
            pred_pre = _to_int(item.get("pred_pre"))
            prev_close = close - pred_pre
            change_rate = (pred_pre / prev_close * 100.0) if prev_close > 0 else 0.0
            candles.append(
                DailyCandle(
                    date=parsed,
                    open_price=parse_price(item.get("open_pric", "")),
                    high_price=parse_price(item.get("high_pric", "")),
                    low_price=parse_price(item.get("low_pric", "")),
                    close_price=close,
                    volume=parse_price(item.get("trde_qty", "")),
                    change_rate=change_rate,
                )
            )
        return candles
    except Exception:
        log.error("키움 일봉 조회 실패 stk_cd=%s", stock_code, exc_info=True)
        return []


def fetch_historical_minute_candles(stock_code: str, base_date: date) -> list[MinuteCandle]:
    """과거 거래일 분봉 — 기준일부터 거꾸로 900봉 한 페이지.

    **캐시하지 않는다.** 받은 페이지의 완성된 날은 `leadingstock.minute_archive`가 날짜 단위로
    4일 보관한다 — 페이지까지 들고 있으면 같은 과거 봉을 두 벌 쥔다.
    """
    return _fetch_minute_candles_raw(stock_code, base_date)


def _fetch_minute_candles_raw(stock_code: str, base_date: date) -> list[MinuteCandle]:
    """1분봉 (ka10080).

    KRX 기본 코드는 정규장 봉만 준다 — SOR 통합(`_AL`)이라야 NXT 프리·애프터 봉이 함께 온다.
    """
    try:
        log.info("키움 분봉 조회 stk_cd=%s base=%s", stock_code, base_date)
        response = client.get_client().post(
            _CHART_URL,
            headers=client.query_headers("ka10080"),
            json={
                "stk_cd": _with_sor_suffix(stock_code),
                "tic_scope": "1",
                "upd_stkpc_tp": "1",
                "base_dt": base_date.strftime("%Y%m%d"),
            },
        )
        response.raise_for_status()
        items = response.json().get("stk_min_pole_chart_qry") or []

        candles = []
        for item in items:
            at = _parse_datetime(item.get("cntr_tm", ""))
            if at is None:
                continue
            close = parse_price(item.get("cur_prc", ""))
            volume = parse_price(item.get("trde_qty", ""))
            candles.append(
                MinuteCandle(
                    date_time=at,
                    open_price=parse_price(item.get("open_pric", "")),
                    high_price=parse_price(item.get("high_pric", "")),
                    low_price=parse_price(item.get("low_pric", "")),
                    close_price=close,
                    volume=volume,
                    # ka10080 응답엔 거래대금 필드가 없다 — 종가 × 거래량으로 근사한다
                    # (단일 분봉이라 가격 변동이 작아 충분).
                    trading_value=close * volume,
                )
            )
        return candles
    except Exception:
        log.error("키움 분봉 조회 실패 stk_cd=%s", stock_code, exc_info=True)
        return []


def _to_int(value: str | None) -> int:
    try:
        return int((value or "").strip())
    except (ValueError, AttributeError):
        return 0


def _to_float(value: str | None) -> float:
    try:
        return float((value or "").strip())
    except (ValueError, AttributeError):
        return 0.0


def _parse_date(value: str) -> date | None:
    text = (value or "").strip()
    if not text:
        return None
    try:
        return datetime.strptime(text, "%Y%m%d").date()
    except ValueError:
        return None


def _parse_datetime(value: str) -> datetime | None:
    text = (value or "").strip()
    if not text:
        return None
    try:
        return datetime.strptime(text, "%Y%m%d%H%M%S")
    except ValueError:
        return None
