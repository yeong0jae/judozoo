package at.backend.market.application

import at.backend.platform.yahoo.client.YahooChartClient
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service

/**
 * 나스닥100 선물(CME NQ) — 야후 파이낸스. KIS는 CME가 유료시세라 못 쓴다.
 * 심볼 "NQ=F"는 야후가 근월물로 자동 연결한다. 우리 장중 미국 심리를 읽는 용도라 등락률·차트만 본다.
 */
@Service
class MarketNasdaqFuturesService(
    private val client: YahooChartClient,
) {

    /** 나스닥 선물 시세 — 전일 종가 대비 등락. 데이터 없으면 null. */
    @Cacheable("nasdaqFuturesQuote", unless = "#result == null")
    fun quote(): NasdaqFuturesQuote? {
        val q = client.fetchQuote(SYMBOL) ?: return null
        val change = q.price - q.prevClose
        return NasdaqFuturesQuote(
            price = q.price,
            prevClose = q.prevClose,
            priceChange = change,
            changeRate = if (q.prevClose == 0.0) 0.0 else change / q.prevClose * 100,
        )
    }

    /** 나스닥 선물 캔들 — interval "1m"(최근 2일)/"1d"(최근 6개월). */
    @Cacheable("nasdaqFuturesCandles", key = "#interval", unless = "#result.isEmpty()")
    fun candles(interval: String): List<YahooChartClient.YahooBar> = when (interval) {
        "1m" -> client.fetchCandles(SYMBOL, "1m", "2d")
        "1d" -> client.fetchCandles(SYMBOL, "1d", "6mo")
        else -> emptyList()
    }

    companion object {
        private const val SYMBOL = "NQ=F" // 나스닥100 선물 근월물
    }
}

/** 나스닥100 선물 시세. 값은 지수 포인트(USD). */
data class NasdaqFuturesQuote(
    val price: Double,
    val prevClose: Double, // 전일 종가
    val priceChange: Double, // 전일 대비(포인트)
    val changeRate: Double, // 전일 대비 등락률(%)
)
