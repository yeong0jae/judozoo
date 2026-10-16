package judozoo.overseasleadingstock.application

import judozoo.library.EntityNotFoundException
import judozoo.library.TtlCache
import judozoo.market.application.MarketCalendar
import judozoo.market.domain.Region
import judozoo.overseasleadingstock.domain.FilterResult
import judozoo.overseasleadingstock.domain.OverseasCriteria
import judozoo.overseasleadingstock.domain.OverseasDailyCandle
import judozoo.overseasleadingstock.domain.OverseasMinuteCandle
import judozoo.overseasleadingstock.domain.OverseasStockRank
import judozoo.overseasleadingstock.domain.OverseasStockRanks
import judozoo.overseasleadingstock.domain.UsRegularSession
import judozoo.platform.kis.KisOverseasChart
import judozoo.platform.kis.KisOverseasProduct
import judozoo.platform.kis.KisOverseasRanking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration

@Service
class OverseasLeadingStockService(
    private val ranking: KisOverseasRanking,
    private val product: KisOverseasProduct,
    private val chart: KisOverseasChart,
    private val minutes: OverseasMinutes,
    private val archive: OverseasMinuteArchive,
    private val calendar: MarketCalendar,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val criteria = OverseasCriteria()

    private val pool = TtlCache<Unit, List<OverseasStockRank>>("overseasTradingValuePool", ::poolTtl, maxSize = 1, serveStale = true)

    data class StockEvaluation(val stock: OverseasStockRank, val marketCap: Long?, val filters: List<FilterResult>)

    internal fun poolTtl(): Duration {
        return if (calendar.usMarketStatus().inSession) POOL_TTL else calendar.untilUsSession()
    }

    fun refreshTradingValuePool() {
        pool.refresh(Unit, ::loadPool)
    }

    fun candidates(minChangeRate: Double): List<OverseasStockRank> = tradingValuePool().filter { it.rate >= minChangeRate }

    fun leaders(count: Int): List<OverseasStockRank> = OverseasStockRanks(tradingValuePool()).leaders(count)

    fun evaluate(exchange: String, symbol: String): StockEvaluation {
        val stock = tradingValuePool().firstOrNull { it.exchange == exchange && it.symbol == symbol }
            ?: throw EntityNotFoundException("후보에 없는 종목: $exchange:$symbol")

        val marketCap = product.fetchMarketCap(exchange, symbol)
        return StockEvaluation(stock, marketCap, criteria.evaluate(stock, marketCap, sessionOpen(exchange, symbol)))
    }

    fun minuteCandles(exchange: String, symbol: String): List<OverseasMinuteCandle> =
        minutes.minuteCandles(exchange, symbol).sortedBy { it.dateTime }

    fun dailyCandles(exchange: String, symbol: String): List<OverseasDailyCandle> =
        chart.fetchDailyCandles(exchange, symbol).sortedBy { it.date }

    fun settleMinutes(): Int {
        val failures = minutes.settle()
        try {
            archive.purge(Region.US.today(clock.instant()).minus(MINUTE_ARCHIVE_RETENTION))
        } catch (e: Exception) {
            log.warn("해외 지난 날 분봉 정리 실패", e)
        }
        return failures
    }

    private fun tradingValuePool(): List<OverseasStockRank> = pool.get(Unit, ::loadPool)

    private fun loadPool(): List<OverseasStockRank> =
        EXCHANGES.flatMap { ranking.fetchTradingValueRanking(it) }
            .sortedByDescending { it.tradingValue }
            .mapIndexed { i, r -> r.ranked(i + 1) }
            .take(criteria.topN)
            .filterNot { it.isEtf }

    private fun sessionOpen(exchange: String, symbol: String): Double? {
        val bars = try {
            minutes.minuteCandles(exchange, symbol)
        } catch (e: Exception) {
            log.warn("해외 분봉을 못 받아 시가 조건을 비운다 ({}:{})", exchange, symbol, e)
            return null
        }
        return UsRegularSession.openPrice(bars)
    }

    companion object {
        val EXCHANGES = listOf("NAS", "NYS", "AMS")

        private val POOL_TTL = Duration.ofSeconds(20)

        private val MINUTE_ARCHIVE_RETENTION = java.time.Period.ofDays(14)
    }
}
