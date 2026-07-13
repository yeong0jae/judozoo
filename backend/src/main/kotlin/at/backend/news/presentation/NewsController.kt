package at.backend.news.presentation

import at.backend.library.web.ApiResponse
import at.backend.news.application.NewsService
import at.backend.news.domain.StockNews
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

/** 종목 관련 뉴스·공시 — KIS 종합 시황/공시. (시황분석 종목 상세용) */
@RestController
class NewsController(
    private val service: NewsService,
) {

    /** [exchange](NAS/NYS/AMS)를 주면 해외 종목 뉴스, 없으면 국내 종목 뉴스·공시. */
    @GetMapping("/api/news/stock/{stockCode}")
    fun stockNews(
        @PathVariable stockCode: String,
        @RequestParam(required = false) exchange: String?,
    ): ApiResponse<List<NewsItem>> {
        val news =
            if (exchange.isNullOrBlank()) service.stockNews(stockCode)
            else service.overseasStockNews(exchange, stockCode)
        return ApiResponse.ok(news.map { it.toItem() })
    }

    private fun StockNews.toItem() = NewsItem(
        seqNo = seqNo,
        title = title,
        source = source,
        disclosure = disclosure,
        publishedAt = publishedAt,
    )

    data class NewsItem(
        val seqNo: String,
        val title: String,
        val source: String, // 언론사명 또는 "공시"
        val disclosure: Boolean,
        val publishedAt: LocalDateTime,
    )
}
