"""KIS 종목 마스터 파일(.zip) 다운로드·파싱.

평문 CDN이라 OpenAPI 토큰·레이트리밋과 무관하다 — 인증도 공유 리미터도 타지 않는다.
인코딩은 국내·해외 모두 MS949(cp949).
"""

import io
import logging
import zipfile
from datetime import datetime

import httpx

from backend.library.time import now
from backend.settings import get_settings
from backend.stock.domain import Market, OverseasStock, Stock

log = logging.getLogger(__name__)

_MS949 = "ms949"
_TIMEOUT = httpx.Timeout(connect=15.0, read=30.0, write=30.0, pool=30.0)

# 국내 마스터 — 한 라인 = [고정폭 part1] + [고정폭 part2].
# part1: 0..9 단축코드, 9..21 표준코드(ISIN), 21.. 한글종목명.
# 필요한 건 part1뿐이라 part2(60여 필드)는 파싱하지 않는다.
_PART1_NAME_OFFSET = 21
_KOSPI_PART2_WIDTH = 228
_KOSDAQ_PART2_WIDTH = 222

# 해외 마스터 — 탭 구분. 나머지 컬럼은 쓰지 않는다.
_EXCHANGE_COL = 2
_SYMBOL_COL = 4
_KOREAN_NAME_COL = 6
_ENGLISH_NAME_COL = 7
_ENGLISH_NAME_MAX = 120  # 컬럼 길이 초과분은 버린다(옵션 만기 문구 등이 붙는 경우가 있다)


def _download(url: str) -> str:
    response = httpx.get(url, timeout=_TIMEOUT, follow_redirects=True)
    response.raise_for_status()
    if not response.content:
        raise ValueError(f"종목 마스터 응답이 비어있습니다: {url}")
    with zipfile.ZipFile(io.BytesIO(response.content)) as z:
        names = z.namelist()
        if not names:
            raise ValueError(f"종목 마스터 zip이 비어있습니다: {url}")
        return z.read(names[0]).decode(_MS949, errors="replace")


def fetch_domestic() -> list[Stock]:
    """코스피 + 코스닥 전 종목."""
    settings = get_settings().stock_master
    at = now()
    return _parse_domestic(_download(settings.kospi_url), Market.KOSPI, _KOSPI_PART2_WIDTH, at) + _parse_domestic(
        _download(settings.kosdaq_url), Market.KOSDAQ, _KOSDAQ_PART2_WIDTH, at
    )


def _parse_domestic(text: str, market: Market, part2_width: int, at: datetime) -> list[Stock]:
    stocks = []
    for raw in text.splitlines():
        line = raw.rstrip("\r\n")
        if len(line) <= part2_width + _PART1_NAME_OFFSET:
            continue
        part1 = line[: len(line) - part2_width]
        short_code = part1[0:9].strip()
        if not short_code:
            continue
        stocks.append(
            Stock(
                short_code=short_code,
                standard_code=part1[9:21].strip(),
                name=part1[_PART1_NAME_OFFSET:].strip(),
                market=market,
                created_at=at,
                updated_at=at,
            )
        )
    return stocks


def fetch_overseas() -> list[OverseasStock]:
    """나스닥 · 뉴욕 · 아멕스 전 종목."""
    at = now()
    stocks: list[OverseasStock] = []
    for url in get_settings().stock_master.overseas_urls:
        stocks.extend(_parse_overseas(_download(url), at))
    return stocks


def _parse_overseas(text: str, at: datetime) -> list[OverseasStock]:
    stocks = []
    for line in text.splitlines():
        cols = line.split("\t")
        if len(cols) <= _ENGLISH_NAME_COL:
            continue
        symbol = cols[_SYMBOL_COL].strip()
        exchange = cols[_EXCHANGE_COL].strip()
        if not symbol or not exchange:
            continue
        stocks.append(
            OverseasStock(
                exchange=exchange,
                symbol=symbol,
                name=cols[_KOREAN_NAME_COL].strip() or symbol,
                english_name=cols[_ENGLISH_NAME_COL].strip()[:_ENGLISH_NAME_MAX],
                created_at=at,
                updated_at=at,
            )
        )
    return stocks
