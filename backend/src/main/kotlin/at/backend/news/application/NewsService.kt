package at.backend.news.application

import at.backend.news.domain.StockNews
import at.backend.platform.kis.client.KisNewsClient
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service

/** 종목 관련 뉴스·공시 — KIS. 국내는 종합 시황/공시, 해외(미국)는 해외뉴스종합. */
@Service
class NewsService(
    private val client: KisNewsClient,
) {

    @Cacheable("stockNews", key = "#stockCode", unless = "#result.isEmpty()")
    fun stockNews(stockCode: String): List<StockNews> = client.fetchStockNews(stockCode)

    /** [exchange]는 NAS/NYS/AMS. 거래소코드가 없으면 KIS가 종목 필터를 걸어주지 않는다. */
    @Cacheable("stockNews", key = "#exchange + ':' + #symbol", unless = "#result.isEmpty()")
    fun overseasStockNews(exchange: String, symbol: String): List<StockNews> =
        client.fetchOverseasNews(exchange, symbol)
}
