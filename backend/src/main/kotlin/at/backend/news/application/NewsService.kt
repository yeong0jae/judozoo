package at.backend.news.application

import at.backend.news.domain.StockNews
import at.backend.platform.kis.client.KisNewsClient
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service

/** 종목 관련 뉴스·공시 — KIS 종합 시황/공시. 국내 종목만 지원한다. */
@Service
class NewsService(
    private val client: KisNewsClient,
) {

    @Cacheable("stockNews", key = "#stockCode", unless = "#result.isEmpty()")
    fun stockNews(stockCode: String): List<StockNews> = client.fetchStockNews(stockCode)
}
