package at.backend.market.application

import at.backend.platform.yahoo.client.YahooChartClient
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service

/**
 * 나스닥 종합지수(^IXIC) — 야후 파이낸스.
 * 현물이라 미 정규장(23:30~06:00 KST)에만 움직인다. 우리 장중엔 직전 마감가에 멈춰 있다.
 */
@Service
class MarketNasdaqIndexService(
    private val client: YahooChartClient,
) {

    /** 나스닥 지수 — 전일 종가 대비 등락. 데이터 없으면 null. */
    @Cacheable("nasdaqIndexQuote", unless = "#result == null")
    fun quote(): NasdaqIndexQuote? {
        val q = client.fetchQuote(SYMBOL) ?: return null
        val change = q.price - q.prevClose
        return NasdaqIndexQuote(
            price = q.price,
            prevClose = q.prevClose,
            priceChange = change,
            changeRate = if (q.prevClose == 0.0) 0.0 else change / q.prevClose * 100,
        )
    }

    /** 나스닥 지수 캔들 — interval "1m"(최근 2일)/"1d"(최근 6개월). */
    @Cacheable("nasdaqIndexCandles", key = "#interval", unless = "#result.isEmpty()")
    fun candles(interval: String): List<YahooChartClient.YahooBar> = when (interval) {
        "1m" -> client.fetchCandles(SYMBOL, "1m", "2d")
        "1d" -> client.fetchCandles(SYMBOL, "1d", "6mo")
        else -> emptyList()
    }

    companion object {
        private const val SYMBOL = "^IXIC" // 나스닥 종합지수
    }
}

/** 나스닥 종합지수 시세. */
data class NasdaqIndexQuote(
    val price: Double,
    val prevClose: Double, // 전일 종가
    val priceChange: Double, // 전일 대비(포인트)
    val changeRate: Double, // 전일 대비 등락률(%)
)
