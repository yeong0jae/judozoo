package at.backend.market.application

import at.backend.library.time.TimeProvider
import at.backend.platform.toss.client.TossMarketIndicatorClient
import at.backend.platform.toss.client.TossMarketIndicatorClient.TossCandle
import at.backend.stock.domain.Market
import org.springframework.stereotype.Service

/** 시장 지수(코스피/코스닥) 캔들 — 토스 Market Indicators. 일봉은 단발 조회, 분봉은 오늘 하루치를 페이지네이션으로 모은다. */
@Service
class MarketCandleService(
    private val client: TossMarketIndicatorClient,
    private val timeProvider: TimeProvider,
) {

    /** 최근 [count]봉 일봉(오름차순). */
    fun daily(market: Market, count: Int): List<TossCandle> =
        client.fetchCandles(market.name, "1d", count.coerceIn(1, MAX_PAGE_SIZE))
            .candles.sortedBy { it.timestamp }

    /** 오늘 하루 1분봉(오름차순) — 한 페이지(최대 200봉)로 정규장을 못 덮어 nextBefore로 이어 받는다. */
    fun minuteToday(market: Market): List<TossCandle> {
        val today = timeProvider.today()
        val collected = mutableListOf<TossCandle>()
        var before: String? = null
        var page = 0
        while (page < MAX_PAGES) {
            val res = client.fetchCandles(market.name, "1m", MAX_PAGE_SIZE, before)
            if (res.candles.isEmpty()) break
            collected += res.candles
            val earliestDate = res.candles.minOf { it.timestamp }.toLocalDate()
            if (earliestDate < today || res.nextBefore == null) break
            before = res.nextBefore
            page++
        }
        return collected.filter { it.timestamp.toLocalDate() == today }.sortedBy { it.timestamp }
    }

    companion object {
        private const val MAX_PAGE_SIZE = 200
        private const val MAX_PAGES = 3 // 200×3=600봉 — 정규장(390분) 여유 있게 커버
    }
}
