package at.backend.stock.application

import at.backend.library.time.TimeProvider
import at.backend.platform.kis.client.KisRealQuotationClient
import at.backend.platform.kis.client.KisRestClient
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class StockService(
    private val kisRestClient: KisRestClient,
    private val kisRealQuotationClient: KisRealQuotationClient,
    private val timeProvider: TimeProvider,
) {

    fun search(query: String): List<StockSearchResult> {
        // search-stock-info는 VTS 미지원이라 실거래 자격증명을 쓰는 별도 클라이언트로 호출
        val output = kisRealQuotationClient.searchStock(query) ?: return emptyList()
        // KIS pdno는 12자리 패딩 (예: "00000A005930"). 거래 ID로는 6자리만 사용한다.
        return listOf(StockSearchResult(stockCode = query, stockName = output.prdtAbrvName))
    }

    fun getPrice(stockCode: String): StockPriceResult {
        val price = kisRestClient.getCurrentPrice(stockCode).output.stckPrpr.toLong()
        return StockPriceResult(stockCode = stockCode, currentPrice = price, asOf = timeProvider.now())
    }

    data class StockSearchResult(val stockCode: String, val stockName: String)
    data class StockPriceResult(val stockCode: String, val currentPrice: Long, val asOf: LocalDateTime)
}
