package at.backend.market.application

import java.time.LocalDate
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

    /** 가장 최근 2 영업일의 1분봉(오름차순) — 2일치 정규장을 덮기 위해 여러 페이지를 조회한다. */
    fun minuteToday(market: Market): List<TossCandle> {
        val collected = mutableListOf<TossCandle>()
        var before: String? = null
        var page = 0
        val targetDates = mutableSetOf<LocalDate>()
        while (page < MAX_PAGES) {
            val res = client.fetchCandles(market.name, "1m", MAX_PAGE_SIZE, before)
            if (res.candles.isEmpty()) break

            collected += res.candles
            val pageDates = res.candles.map { it.timestamp.toLocalDate() }
            targetDates.addAll(pageDates)

            // 고유 거래일이 3개 이상 감지되면 2일치 수집이 완료된 것이므로 종료
            if (targetDates.size > 2 || res.nextBefore == null) {
                break
            }
            before = res.nextBefore
            page++
        }
        val validDates = targetDates.sortedDescending().take(2).toSet()
        return collected.filter { it.timestamp.toLocalDate() in validDates }.sortedBy { it.timestamp }
    }

    companion object {
        private const val MAX_PAGE_SIZE = 200
        private const val MAX_PAGES = 6 // 200×6=1200봉 — 2일치 정규장(780분) 여유 있게 커버
    }
}
