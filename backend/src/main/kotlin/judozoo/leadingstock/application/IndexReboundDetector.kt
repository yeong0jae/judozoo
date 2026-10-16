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

/**
 * 지수 반등·꺾임 — 지수 5분봉 종가가 20이평을 뚫는 순간을 적재한다(30초마다, 정규장만).
 *
 * 반등/꺾임은 각자 무장 상태에서 해당 방향 돌파봉이 나올 때 1회 발화하고 해제한다.
 * 반대로 이평 대비 마진 이상 밀려야 재무장해 잔떨림을 막는다.
 *
 * 키움은 최근 4분 남짓의 10초 틱만 준다 — 매 폴 받은 분봉을 메모리에 쌓고 DB(`index_minute_candle`)에 그대로 쓴다.
 * 재시작 직후엔 그날치를 DB에서 한 번 끌어온다. 백필은 없다 — 한도 보호.
 */
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
            // 누적 저장소로 판정한다 — 4분 페이지 경계에서 이평이 잘리지 않게
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

    /** 메모리에 병합하고 같은 값을 DB에 그대로 쓴다(진행 중인 분은 더 완성된 값으로 갈아 끼운다). */
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
