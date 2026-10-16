package judozoo.leadingstock.application

import judozoo.leadingstock.domain.IndexMinuteCandle
import judozoo.leadingstock.domain.IndexMinuteCandleEntity
import judozoo.leadingstock.domain.IndexMinuteCandleRepository
import judozoo.leadingstock.domain.IndexMinuteCandles
import judozoo.leadingstock.domain.MarketSignalEvent
import judozoo.leadingstock.domain.MarketSignalEventRepository
import judozoo.leadingstock.domain.MarketSignalType
import judozoo.library.KST
import judozoo.platform.kiwoom.KiwoomIndex
import judozoo.stock.domain.Market
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.TreeMap

@Component
class IndexReboundDetector(
    private val index: KiwoomIndex,
    private val candles: IndexMinuteCandleRepository,
    private val events: MarketSignalEventRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private var day: LocalDate? = null
    private val reboundArmed = HashMap<Market, Boolean>()
    private val breakdownArmed = HashMap<Market, Boolean>()
    private val stores = HashMap<Market, TreeMap<LocalDateTime, IndexMinuteCandle>>()

    @Synchronized
    fun detect() {
        val at = LocalDateTime.now(clock.withZone(KST))
        val today = at.toLocalDate()
        if (day != today) {
            reboundArmed.clear()
            breakdownArmed.clear()
            stores.clear()
            day = today
        }

        val recorded = mutableListOf<MarketSignalEvent>()
        for (market in Market.entries) {
            val intraday = index.fetchIndexIntraday(market, today) ?: continue
            val fresh = IndexMinuteCandles.fromTicks(intraday.ticks).candles()
            val ma = IndexMinuteCandles(merge(market, today, fresh, at)).movingAverage(MA_INTERVAL_MINUTES, MA_PERIOD, MA_REARM_MARGIN) ?: continue
            fun cross(kind: MarketSignalType) = MarketSignalEvent.indexCross(kind, at, today, market, intraday.value, intraday.changeRate)

            if (reboundArmed.getOrDefault(market, true) && ma.crossedUp) {
                reboundArmed[market] = false
                recorded += cross(MarketSignalType.MA_REBOUND)
            } else if (ma.belowBand) {
                reboundArmed[market] = true
            }
            if (breakdownArmed.getOrDefault(market, true) && ma.crossedDown) {
                breakdownArmed[market] = false
                recorded += cross(MarketSignalType.MA_BREAKDOWN)
            } else if (ma.aboveBand) {
                breakdownArmed[market] = true
            }
        }
        if (recorded.isEmpty()) return
        events.saveAll(recorded)
        log.info("지수 반등·꺾임 {}건 적재", recorded.size)
    }

    private fun merge(market: Market, today: LocalDate, fresh: List<IndexMinuteCandle>, at: LocalDateTime): List<IndexMinuteCandle> {
        val store = stores.getOrPut(market) {
            TreeMap(candles.findByMarketAndTradeDateBetweenOrderByMinuteAt(market, today, today).associate { it.minuteAt to it.toCandle() })
        }
        fresh.forEach { store[it.minute] = it }
        val existing = candles.findByMarketAndMinuteAtIn(market, fresh.map { it.minute }).associateBy { it.minuteAt }
        candles.saveAll(
            fresh.map { c ->
                existing[c.minute]?.apply { refresh(c, at) } ?: IndexMinuteCandleEntity(market, c.minute.toLocalDate(), c.minute, c, at)
            },
        )
        return store.values.toList()
    }

    companion object {
        private const val MA_INTERVAL_MINUTES = 5
        private const val MA_PERIOD = 20
        private const val MA_REARM_MARGIN = 0.005
    }
}
