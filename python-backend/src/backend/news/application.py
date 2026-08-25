"""종목 관련 뉴스·공시 — KIS. 국내는 종합 시황/공시, 해외(미국)는 해외뉴스종합.

캐시 이름·TTL·키 구성은 Kotlin `NewsService`와 같다. 국내와 해외가 `stockNews`
캐시를 공유하되 키 모양이 달라(종목코드 하나 vs 거래소+심볼) 서로 덮어쓰지 않는다.
"""

from backend.library.cache import is_empty, ttl_cache
from backend.news.domain import StockNews
from backend.platform.kis import news as kis_news

_STOCK_NEWS_CACHE = dict(name="stockNews", ttl_seconds=60.0, maxsize=100, skip_if=is_empty)


@ttl_cache(**_STOCK_NEWS_CACHE)
def stock_news(stock_code: str) -> list[StockNews]:
    return kis_news.fetch_stock_news(stock_code)


@ttl_cache(**_STOCK_NEWS_CACHE)
def overseas_stock_news(exchange: str, symbol: str) -> list[StockNews]:
    """[exchange]는 NAS/NYS/AMS. 거래소코드가 없으면 KIS가 종목 필터를 걸어주지 않는다."""
    return kis_news.fetch_overseas_news(exchange, symbol)
