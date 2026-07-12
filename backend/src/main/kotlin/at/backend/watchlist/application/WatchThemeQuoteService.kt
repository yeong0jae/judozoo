package at.backend.watchlist.application

import at.backend.platform.kiwoom.client.KiwoomMarketClient
import org.springframework.stereotype.Service

/**
 * 관심 종목 시세 — 코드별로 키움 ka10001을 부른다(5초 캐시).
 * 화면에 보이는 테마의 종목만 요청받는다. 전체 테마를 매번 부르면 키움 rate limit에 걸린다.
 */
@Service
class WatchThemeQuoteService(
    private val kiwoom: KiwoomMarketClient,
) {

    fun quotes(stockCodes: List<String>): List<StockQuote> =
        stockCodes.distinct().mapNotNull { code ->
            kiwoom.fetchStockDetail(code)?.let {
                StockQuote(
                    stockCode = code,
                    stockName = it.stockName,
                    currentPrice = it.currentPrice,
                    priceChangeRate = it.priceChangeRate,
                )
            }
        }
}

/** 종목 시세 — 관심 테마 리스트 표시용. */
data class StockQuote(
    val stockCode: String,
    val stockName: String,
    val currentPrice: Long,
    val priceChangeRate: Double,
)
