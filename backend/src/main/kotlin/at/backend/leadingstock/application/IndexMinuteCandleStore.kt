package at.backend.leadingstock.application

import at.backend.leadingstock.domain.IndexMinuteCandle
import at.backend.library.time.TimeProvider
import at.backend.stock.domain.Market
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * 당일 지수 1분봉 누적 캐시(메모리) — 연속 판정 핫패스용. 폴러가 30초마다 받은 최근 ~4분치를 분 단위로 병합하고,
 * 같은 값을 DB에도 라이트스루(upsert)한다. 재시작 시엔 DB에서 그날치를 한 번 끌어와(rehydrate) 연속이 정확하다.
 * 일자가 바뀌면 메모리를 비운다.
 */
@Component
class IndexMinuteCandleStore(
    private val timeProvider: TimeProvider,
    private val persistence: IndexMinuteCandleService,
) {
    private val byMarket = ConcurrentHashMap<Market, ConcurrentHashMap<LocalDateTime, IndexMinuteCandle>>()
    private val loaded = ConcurrentHashMap.newKeySet<Market>()
    private val tradeDate = AtomicReference<LocalDate?>(null)

    fun merge(market: Market, candles: List<IndexMinuteCandle>) {
        rolloverIfNeeded()
        ensureLoaded(market)
        val map = byMarket.getOrPut(market) { ConcurrentHashMap() }
        candles.forEach { map[it.minute] = it }
        persistence.upsertAll(market, candles)
    }

    fun candles(market: Market): List<IndexMinuteCandle> {
        rolloverIfNeeded()
        ensureLoaded(market)
        return byMarket[market]?.values?.sortedBy { it.minute } ?: emptyList()
    }

    /** 재시작 후 메모리가 비어 있으면 그날치를 DB에서 한 번 끌어온다. */
    private fun ensureLoaded(market: Market) {
        if (!loaded.add(market)) return
        val map = byMarket.getOrPut(market) { ConcurrentHashMap() }
        persistence.candlesOn(market, timeProvider.today()).forEach { map[it.minute] = it }
    }

    private fun rolloverIfNeeded() {
        val today = timeProvider.today()
        if (tradeDate.getAndSet(today) != today) {
            byMarket.clear()
            loaded.clear()
        }
    }
}
