package judozoo.overseasleadingstock.application

import judozoo.library.EntityNotFoundException
import judozoo.library.KST
import judozoo.library.TtlCache
import judozoo.market.application.MarketCalendar
import judozoo.market.domain.Region
import judozoo.overseasleadingstock.domain.FilterResult
import judozoo.overseasleadingstock.domain.OverseasStockRank
import judozoo.overseasleadingstock.domain.OverseasStockRanks
import judozoo.overseasleadingstock.domain.UsRegularSession
import judozoo.platform.kis.KisOverseasChart
import judozoo.platform.kis.KisOverseasChart.OverseasDailyCandle
import judozoo.platform.kis.KisOverseasChart.OverseasMinuteCandle
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

    private val pool = TtlCache<Unit, List<OverseasStockRank>>("overseasTradingValuePool", ::poolTtl, maxSize = 1, serveStale = true)

    data class StockEvaluation(val stock: OverseasStockRank, val marketCap: Long?, val filters: List<FilterResult>)

    internal fun poolTtl(): Duration {
        val (holiday, tradingHours) = calendar.usMarketStatus()
        return if (!holiday && tradingHours) POOL_TTL else calendar.untilUsSession()
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
        val sessionOpen = sessionOpen(exchange, symbol)
        val fromOpen = sessionOpen?.let { (stock.price - it) / it * 100 }
        val prevClose = stock.previousClose
        val gap = if (sessionOpen != null && prevClose > 0) (sessionOpen - prevClose) / prevClose * 100 else null

        val filters = listOf(
            FilterResult("거래대금순위", "통합 상위 ${TOP_N}위 이내", "${stock.rank}위", stock.rank <= TOP_N, stock.rank.toDouble(), TOP_N.toDouble()),
            FilterResult(
                "당일 등락률", "${MIN_CHANGE_RATE_PCT.toInt()}% 이상", "%+.2f%%".fmt(stock.rate),
                stock.rate >= MIN_CHANGE_RATE_PCT, stock.rate, MIN_CHANGE_RATE_PCT,
            ),
            FilterResult(
                "시가총액", "$${MIN_MARKET_CAP_USD / 1_000_000_000}B 이상", marketCap?.let(::formatUsdCap) ?: "조회 불가",
                marketCap != null && marketCap >= MIN_MARKET_CAP_USD,
            ),
            FilterResult(
                "시가 대비 현재가", "현재가 ≥ 시가", fromOpen?.let { "%+.2f%%".fmt(it) } ?: "시가 없음",
                fromOpen != null && fromOpen >= 0, fromOpen, 0.0,
            ),
            FilterResult(
                "시초가", "시초가 $MAX_OPENING_GAP_PCT% 이하",
                if (gap != null) "$%,.2f (%+.2f%%)".fmt(sessionOpen, gap) else "시초가 없음",
                gap != null && gap <= MAX_OPENING_GAP_PCT, gap, MAX_OPENING_GAP_PCT,
            ),
        )
        return StockEvaluation(stock, marketCap, filters)
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
            .map(::toRank)
            .sortedByDescending { it.tradingValue }
            .mapIndexed { i, r -> r.ranked(i + 1) }
            .take(TOP_N)
            .filterNot { it.isEtf }

    private fun sessionOpen(exchange: String, symbol: String): Double? {
        val bars = try {
            minutes.minuteCandles(exchange, symbol)
        } catch (e: Exception) {
            log.warn("해외 분봉을 못 받아 시가 조건을 비운다 ({}:{})", exchange, symbol, e)
            return null
        }
        if (bars.isEmpty()) return null
        val day = bars.maxOf { it.tradingDay }
        return UsRegularSession.openPrice(
            bars.filter { it.tradingDay == day }.map { it.dateTime.atZone(KST).withZoneSameInstant(Region.US.zone).toLocalDateTime() to it.open },
        )
    }

    companion object {
        val EXCHANGES = listOf("NAS", "NYS", "AMS")
        const val TOP_N = 40

        const val MIN_CHANGE_RATE_PCT = 5.0

        const val MIN_MARKET_CAP_USD = 2_000_000_000L

        const val MAX_OPENING_GAP_PCT = 7.0

        private val POOL_TTL = Duration.ofSeconds(20)

        private val MINUTE_ARCHIVE_RETENTION = java.time.Period.ofDays(14)

        private fun String.fmt(vararg args: Any?) = String.format(java.util.Locale.ROOT, this, *args)

        fun formatUsdCap(usd: Long): String =
            if (usd >= 1_000_000_000_000) "$%.2fT".fmt(usd / 1_000_000_000_000.0) else "$%,dB".fmt(usd / 1_000_000_000)

        private fun toRank(item: KisOverseasRanking.OverseasRankItem): OverseasStockRank {
            val sign = if (item.sign.trim() in setOf("4", "5")) -1.0 else 1.0
            fun number(value: String) = value.trim().toDoubleOrNull() ?: 0.0
            return OverseasStockRank(
                rank = 0,
                exchange = item.excd.trim(), symbol = item.symb.trim(), name = item.name.trim(), ename = item.ename.trim(),
                price = number(item.last), diff = sign * number(item.diff), rate = number(item.rate), tradingValue = number(item.tamt),
            )
        }
    }
}
