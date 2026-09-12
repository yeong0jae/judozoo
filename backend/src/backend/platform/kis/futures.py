"""국내 지수선물(코스피200·코스닥150) 시세 — KIS 국내선물옵션.

- 근월물: 전광판_선물(FHPIF05030200)에서 잔존일수 최소 종목
- 일봉+요약: 기간별시세(FHKIF03020100) output1(선물·현물·미결제·괴리율) + output2(일봉 OHLC)
- 분봉: 분봉조회(FHKIF03020200) output2
- 시세: 선물옵션 시세(FHMIF10000000)
- 투자자: 시장별 투자자매매동향(FHPTJ04030000)

실패·응답오류·파싱불가는 None/빈 목록 — 호출측이 스킵하게 한다.
"""

import logging
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta

from backend.platform.kis import client
from backend.stock.domain import Market

log = logging.getLogger(__name__)

DAY = "F"    # 정규장 지수선물
NIGHT = "CM"  # 야간선물

_BOARD_URL = "/uapi/domestic-futureoption/v1/quotations/display-board-futures"
_DAILY_URL = "/uapi/domestic-futureoption/v1/quotations/inquire-daily-fuopchartprice"
_MINUTE_URL = "/uapi/domestic-futureoption/v1/quotations/inquire-time-fuopchartprice"
_PRICE_URL = "/uapi/domestic-futureoption/v1/quotations/inquire-price"
_INVESTOR_URL = "/uapi/domestic-stock/v1/quotations/inquire-investor-time-by-market"

# KIS 선물 시장 구분 코드 — KIS 전용이라 도메인이 아니라 여기 둔다.
_FUTURES_CLS_CODE = {Market.KOSPI: "", Market.KOSDAQ: "KQI"}       # 전광판: 공백=KOSPI200
_INVESTOR_ISCD = {Market.KOSPI: "K2I", Market.KOSDAQ: "KQI"}       # K2I=코스피200 선물·콜·풋
_INVESTOR_ISCD2 = {Market.KOSPI: "F001", Market.KOSDAQ: "F002"}    # F001=코스피200선물

_NEGATIVE_SIGNS = {"4", "5"}  # 부호코드 1상한 2상승 3보합 4하한 5하락


@dataclass(frozen=True)
class NearMonth:
    iscd: str
    name: str
    rmnn_days: int


@dataclass(frozen=True)
class FuturesBar:
    date: str  # yyyy-MM-dd
    time: str  # HH:mm:ss (일봉은 00:00:00)
    open: float
    high: float
    low: float
    close: float
    volume: float


@dataclass(frozen=True)
class FuturesSummary:
    name: str
    futures_price: float
    change_rate: float
    spot: float
    basis: float  # 시장 베이시스 = 선물 − 현물
    dprt: float
    open_interest: int
    open_interest_change: int


@dataclass(frozen=True)
class FuturesDaily:
    summary: FuturesSummary
    candles: list[FuturesBar]


@dataclass(frozen=True)
class FuturesPrice:
    """선물 현재가 스냅샷. 야간(CM)이면 `price_change`가 직전 정규장 종가 대비 갭."""

    name: str
    price: float
    prev_close: float
    price_change: float
    change_rate: float
    open: float
    high: float
    low: float
    volume: int
    open_interest: int
    open_interest_change: int


@dataclass(frozen=True)
class FuturesInvestors:
    """선물 시장 투자자별 순매수(계약). 양수=순매수. 기관 세부는 기관계의 내역."""

    foreign: int
    individual: int
    institution: int
    securities: int      # 증권
    insurance: int       # 보험
    merchant_bank: int   # 종금
    trust: int           # 투자신탁
    private_equity: int  # 사모펀드
    fund: int            # 기금
    bank: int            # 은행
    other_org: int       # 기타단체
    other_corp: int      # 기타법인


def _signed(ctrt: str | None, sign: str | None) -> float:
    """등락률 크기에 부호코드를 적용해 부호 포함 %로."""
    magnitude = abs(_to_float(ctrt))
    return -magnitude if (sign or "").strip() in _NEGATIVE_SIGNS else magnitude


def fetch_near_month(market: Market) -> NearMonth | None:
    """선물 근월물(잔존일수 최소, 만기 지난 것 제외). 없으면 None."""
    try:
        body = _get(
            _BOARD_URL,
            {
                "FID_COND_MRKT_DIV_CODE": "F",
                "FID_COND_SCR_DIV_CODE": "20503",
                "FID_COND_MRKT_CLS_CODE": _FUTURES_CLS_CODE[market],
            },
            "FHPIF05030200",
            "선물 전광판",
        )
        if body is None:
            return None

        candidates = []
        for row in body.get("output") or []:  # 전광판은 응답 키가 output(단수)
            iscd = (row.get("futs_shrn_iscd") or "").strip()
            days = _to_int_or_none((row.get("hts_rmnn_dynu") or "").strip())
            if not iscd or days is None or days < 0:
                continue
            candidates.append(NearMonth(iscd, (row.get("hts_kor_isnm") or "").strip() or iscd, days))
        return min(candidates, key=lambda n: n.rmnn_days) if candidates else None
    except Exception:
        log.error("KIS 선물 근월물 조회 실패", exc_info=True)
        return None


def fetch_daily(iscd: str, from_: date, to: date, market: str = DAY) -> FuturesDaily | None:
    """근월물 기간별(일봉) 시세 — 요약 + 일봉 OHLC."""
    try:
        body = _get(
            _DAILY_URL,
            {
                "FID_COND_MRKT_DIV_CODE": market,
                "FID_INPUT_ISCD": iscd,
                "FID_INPUT_DATE_1": from_.strftime("%Y%m%d"),
                "FID_INPUT_DATE_2": to.strftime("%Y%m%d"),
                "FID_PERIOD_DIV_CODE": "D",
            },
            "FHKIF03020100",
            f"선물 일봉 (iscd={iscd})",
        )
        if body is None:
            return None

        o = body.get("output1")
        if not o:
            return None
        futures_price = _to_float_or_none(o.get("futs_prpr"))
        spot = _to_float_or_none(o.get("kospi200_nmix"))
        if futures_price is None or spot is None:
            return None

        summary = FuturesSummary(
            name=(o.get("hts_kor_isnm") or "").strip(),
            futures_price=futures_price,
            change_rate=_signed(o.get("futs_prdy_ctrt"), o.get("prdy_vrss_sign")),
            spot=spot,
            # 시장 베이시스 = 선물 − 현물. KIS의 basis 필드는 이론가 − 현물(캐리)이라 쓰지 않는다.
            basis=futures_price - spot,
            dprt=_to_float(o.get("dprt")),
            open_interest=_to_int(o.get("hts_otst_stpl_qty")),
            open_interest_change=_to_int(o.get("otst_stpl_qty_icdc")),
        )
        candles = [b for b in (_daily_bar(i) for i in (body.get("output2") or [])) if b]
        candles.sort(key=lambda b: b.date + b.time)
        return FuturesDaily(summary, candles)
    except Exception:
        log.error("KIS 선물 일봉 조회 실패 iscd=%s", iscd, exc_info=True)
        return None


def fetch_minute(iscd: str, on: date, hour: time | str, market: str = DAY) -> list[FuturesBar]:
    """근월물 1분봉 — 기준 시각 이전 102봉, 시각 오름차순.

    야간은 자정 이후 시각이 +24시간으로 오고 가므로 `hour`를 HHMMSS 문자열로도 받는다
    (예: "253000" = 01:30).
    """
    hhmmss = hour.strftime("%H%M%S") if isinstance(hour, time) else hour
    try:
        body = _get(
            _MINUTE_URL,
            {
                "FID_COND_MRKT_DIV_CODE": market,
                "FID_INPUT_ISCD": iscd,
                "FID_HOUR_CLS_CODE": "60",       # 1분
                "FID_PW_DATA_INCU_YN": "N",      # 당일
                "FID_FAKE_TICK_INCU_YN": "N",
                "FID_INPUT_DATE_1": on.strftime("%Y%m%d"),
                "FID_INPUT_HOUR_1": hhmmss,
            },
            "FHKIF03020200",
            f"선물 분봉 (iscd={iscd}, market={market})",
        )
        if body is None:
            return []
        bars = [b for b in (_minute_bar(i) for i in (body.get("output2") or [])) if b]
        bars.sort(key=lambda b: b.date + b.time)
        return bars
    except Exception:
        log.error("KIS 선물 분봉 조회 실패 iscd=%s market=%s", iscd, market, exc_info=True)
        return []


def fetch_price(iscd: str, market: str) -> FuturesPrice | None:
    """근월물 시세. `market`이 CM이면 야간선물."""
    try:
        body = _get(
            _PRICE_URL,
            {"FID_COND_MRKT_DIV_CODE": market, "FID_INPUT_ISCD": iscd},
            "FHMIF10000000",
            f"선물 시세 (iscd={iscd}, market={market})",
        )
        if body is None:
            return None

        o = body.get("output1")
        if not o:
            return None
        price = _to_float_or_none(o.get("futs_prpr"))
        if price is None:
            return None

        # futs_prdy_clpr은 야간(CM)에서 정규장 종가가 아닌 값이 온다 — 전일 대비로 역산해야 맞는다.
        diff = _signed(o.get("futs_prdy_vrss"), o.get("prdy_vrss_sign"))
        return FuturesPrice(
            name=(o.get("hts_kor_isnm") or "").strip(),
            price=price,
            prev_close=price - diff,
            price_change=diff,
            change_rate=_signed(o.get("futs_prdy_ctrt"), o.get("prdy_vrss_sign")),
            open=_to_float_or_default(o.get("futs_oprc"), price),
            high=_to_float_or_default(o.get("futs_hgpr"), price),
            low=_to_float_or_default(o.get("futs_lwpr"), price),
            volume=_to_int(o.get("acml_vol")),
            open_interest=_to_int(o.get("hts_otst_stpl_qty")),
            open_interest_change=_to_int(o.get("otst_stpl_qty_icdc")),
        )
    except Exception:
        log.error("KIS 선물 시세 조회 실패 iscd=%s market=%s", iscd, market, exc_info=True)
        return None


def fetch_investors(market: Market) -> FuturesInvestors | None:
    """선물 시장 투자자별 순매수(장중 시세성) — 외국인·개인·기관계. 단위: 계약."""
    try:
        body = _get(
            _INVESTOR_URL,
            {
                "fid_input_iscd": _INVESTOR_ISCD[market],
                "fid_input_iscd_2": _INVESTOR_ISCD2[market],
            },
            "FHPTJ04030000",
            "선물 투자자",
        )
        if body is None:
            return None

        rows = body.get("output") or []  # 문서는 Object라 하지만 실제로는 배열로 온다
        if not rows:
            return None
        o = rows[0]
        return FuturesInvestors(
            foreign=_to_int(o.get("frgn_ntby_qty")),
            individual=_to_int(o.get("prsn_ntby_qty")),
            institution=_to_int(o.get("orgn_ntby_qty")),
            # 사모펀드·기타단체·기타법인만 필드명이 _vol, 나머지는 _qty
            securities=_to_int(o.get("scrt_ntby_qty")),
            insurance=_to_int(o.get("insu_ntby_qty")),
            merchant_bank=_to_int(o.get("mrbn_ntby_qty")),
            trust=_to_int(o.get("ivtr_ntby_qty")),
            private_equity=_to_int(o.get("pe_fund_ntby_vol")),
            fund=_to_int(o.get("fund_ntby_qty")),
            bank=_to_int(o.get("bank_ntby_qty")),
            other_org=_to_int(o.get("etc_orgt_ntby_vol")),
            other_corp=_to_int(o.get("etc_corp_ntby_vol")),
        )
    except Exception:
        log.error("KIS 선물 투자자 조회 실패", exc_info=True)
        return None


def _get(url: str, params: dict, tr_id: str, label: str) -> dict | None:
    response = client.get_client().get(url, params=params, headers=client.auth_headers(tr_id))
    response.raise_for_status()
    body = response.json()
    if body.get("rt_cd") != "0":
        log.error("KIS %s 오류 code=%s msg=%s", label, body.get("msg_cd"), body.get("msg1"))
        return None
    return body


def _daily_bar(item: dict) -> FuturesBar | None:
    ymd = (item.get("stck_bsop_date") or "").strip()
    close = _to_float_or_none(item.get("futs_prpr"))
    if len(ymd) != 8 or close is None:
        return None
    return FuturesBar(
        date=f"{ymd[0:4]}-{ymd[4:6]}-{ymd[6:8]}",
        time="00:00:00",
        open=_to_float_or_default(item.get("futs_oprc"), close),
        high=_to_float_or_default(item.get("futs_hgpr"), close),
        low=_to_float_or_default(item.get("futs_lwpr"), close),
        close=close,
        volume=_to_float(item.get("acml_vol")),
    )


def _minute_bar(item: dict) -> FuturesBar | None:
    """야간선물은 한 세션을 한 영업일로 묶으려고 자정 이후 시각을 **+24시간**으로 보낸다(25:30 = 익일 01:30).

    24시 이상이면 날짜를 하루 넘기고 시각에서 24시간을 빼, 프론트가 쓰는 정상 타임스탬프로 되돌린다.
    """
    ymd = (item.get("stck_bsop_date") or "").strip()
    if len(ymd) != 8:
        return None
    hms = ((item.get("stck_cntg_hour") or "").strip() or "000000").rjust(6, "0")
    close = _to_float_or_none(item.get("futs_prpr"))
    hour = _to_int_or_none(hms[0:2])
    if close is None or hour is None:
        return None
    try:
        on = datetime.strptime(ymd, "%Y%m%d").date()
    except ValueError:
        return None
    if hour >= 24:
        on = on + timedelta(days=1)
    return FuturesBar(
        date=on.isoformat(),
        time=f"{hour % 24:02d}:{hms[2:4]}:{hms[4:6]}",
        open=_to_float_or_default(item.get("futs_oprc"), close),
        high=_to_float_or_default(item.get("futs_hgpr"), close),
        low=_to_float_or_default(item.get("futs_lwpr"), close),
        close=close,
        volume=_to_float(item.get("cntg_vol")),
    )


def _to_float_or_none(value: str | None) -> float | None:
    try:
        return float((value or "").strip())
    except (ValueError, AttributeError):
        return None


def _to_float(value: str | None) -> float:
    parsed = _to_float_or_none(value)
    return parsed if parsed is not None else 0.0


def _to_float_or_default(value: str | None, default: float) -> float:
    parsed = _to_float_or_none(value)
    return parsed if parsed is not None else default


def _to_int_or_none(value: str | None) -> int | None:
    try:
        return int((value or "").strip())
    except (ValueError, AttributeError):
        return None


def _to_int(value: str | None) -> int:
    parsed = _to_int_or_none(value)
    return parsed if parsed is not None else 0
