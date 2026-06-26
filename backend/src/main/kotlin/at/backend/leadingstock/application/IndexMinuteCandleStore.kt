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
 * 당일 지수 1분봉 누적 저장소(메모리). 폴러가 30초마다 받은 최근 ~4분치를 분 단위로 병합해 하루를 채우고,
 * 차트 엔드포인트는 추가 API 호출 없이 여기서 읽는다. 같은 분은 더 완성된(최신) 봉으로 덮어쓴다.
 * 일자가 바뀌면 통째로 비운다.
 */
@Component
class IndexMinuteCandleStore(
    private val timeProvider: TimeProvider,
) {
    private val byMarket = ConcurrentHashMap<Market, ConcurrentHashMap<LocalDateTime, IndexMinuteCandle>>()
    private val tradeDate = AtomicReference<LocalDate?>(null)

    fun merge(market: Market, candles: List<IndexMinuteCandle>) {
        rolloverIfNeeded()
        val map = byMarket.getOrPut(market) { ConcurrentHashMap() }
        candles.forEach { map[it.minute] = it }
    }

    fun candles(market: Market): List<IndexMinuteCandle> {
        rolloverIfNeeded()
        return byMarket[market]?.values?.sortedBy { it.minute } ?: emptyList()
    }

    private fun rolloverIfNeeded() {
        val today = timeProvider.today()
        if (tradeDate.getAndSet(today) != today) byMarket.clear()
    }
}
