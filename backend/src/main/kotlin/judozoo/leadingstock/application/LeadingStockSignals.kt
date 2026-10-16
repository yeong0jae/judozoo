package judozoo.leadingstock.application

import judozoo.leadingstock.domain.IndexMinuteCandle
import judozoo.leadingstock.domain.IndexMinuteCandleRepository
import judozoo.leadingstock.domain.MarketSignalEvent
import judozoo.leadingstock.domain.MarketSignalEventRepository
import judozoo.leadingstock.domain.SignalEvent
import judozoo.leadingstock.domain.SignalEventRepository
import judozoo.market.application.MarketInvestors
import judozoo.market.domain.MarketInvestorSnapshot
import judozoo.stock.domain.Market
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

@Service
@Transactional(readOnly = true)
class LeadingStockSignals(
    private val signalEvents: SignalEventRepository,
    private val marketEvents: MarketSignalEventRepository,
    private val indexCandles: IndexMinuteCandleRepository,
    private val investors: MarketInvestors,
) {
    fun signalEventsOn(day: LocalDate): List<SignalEvent> = signalEvents.findByTradeDateOrderByOccurredAtDesc(day)

    fun marketEventsOn(day: LocalDate): List<MarketSignalEvent> = marketEvents.findByTradeDateOrderByOccurredAtDesc(day)

    fun investorNetBuy(at: LocalDateTime?, now: LocalDateTime): Map<Market, MarketInvestorSnapshot> = Market.entries.mapNotNull { market ->
        val snapshot = if (at != null) investors.investorSnapshotAt(market, at) else investors.latestInvestorSnapshot(market, now)
        snapshot?.let { market to it }
    }.toMap()

    fun indexMinuteCandles(market: Market, day: LocalDate): List<IndexMinuteCandle> =
        indexCandles.findByMarketAndTradeDateBetweenOrderByMinuteAt(market, previousWeekday(day), day).map { it.toCandle() }

    private fun previousWeekday(on: LocalDate): LocalDate {
        var d = on.minusDays(1)
        while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.minusDays(1)
        return d
    }
}
