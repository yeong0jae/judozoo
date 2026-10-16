package judozoo.overseasleadingstock.application

import judozoo.overseasleadingstock.domain.OverseasMinuteCandleEntity
import judozoo.overseasleadingstock.domain.OverseasMinuteCandleRepository
import judozoo.platform.kis.KisOverseasChart.OverseasMinuteCandle
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.LocalDate

/**
 * 해외 종목 지난 거래일 1분봉 보관소 — `(거래소, 종목, 현지 영업일)` → 그날 봉 전체.
 *
 * 처음 여는 종목이 직전 거래일을 KIS에서 다시 받지 않게 한다(2거래일치는 12페이지 안팎이라 몰리면 한도 EGW00201에 걸린다).
 * **완성된 날만 넣는다**([OverseasMinutes]가 넣는 때를 정한다). 메모리 앞, DB(`overseas_minute_candle`) 뒤.
 * DB가 죽어 있으면 메모리만으로 동작한다. 2주 지나면 지운다.
 */
@Component
class OverseasMinuteArchive(
    private val repository: OverseasMinuteCandleRepository,
    private val transaction: TransactionTemplate,
    private val ticker: () -> Long = System::nanoTime,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private data class Key(val exchange: String, val symbol: String, val day: LocalDate)
    private class Remembered(val storedAt: Long, val bars: List<OverseasMinuteCandle>)

    private val days = LinkedHashMap<Key, Remembered>()

    /** 최근 완성본을 메모리 우선으로 읽는다(최근 날부터). DB 장애 중에도 메모리의 지난 봉을 유지한다. */
    fun recent(exchange: String, symbol: String, limit: Int): List<Pair<LocalDate, List<OverseasMinuteCandle>>> {
        val known = synchronized(days) { days.keys.filter { it.exchange == exchange && it.symbol == symbol }.map { it.day } }
        val remembered = sortedMapOf<LocalDate, List<OverseasMinuteCandle>>()
        known.forEach { day -> recall(Key(exchange, symbol, day))?.let { remembered[day] = it } }
        if (remembered.size < limit) {
            try {
                repository.latestDays(exchange, symbol, Limit.of(limit)).filterNot { it in remembered }.forEach { day ->
                    val bars = repository.findByExchangeAndSymbolAndTradingDayOrderByMinuteAt(exchange, symbol, day).map { it.toCandle() }
                    if (bars.isNotEmpty()) {
                        remember(Key(exchange, symbol, day), bars)
                        remembered[day] = bars
                    }
                }
            } catch (e: Exception) {
                log.warn("해외 지난 날 분봉 DB 조회 실패 ({}:{})", exchange, symbol, e)
            }
        }
        return remembered.entries.reversed().take(limit).map { it.key to it.value }
    }

    /** 완성된 하루치를 넣는다. 비어 있으면 넣지 않는다 — 없는 날과 못 받은 날을 섞지 않는다. */
    fun put(exchange: String, symbol: String, day: LocalDate, bars: List<OverseasMinuteCandle>) {
        if (bars.isEmpty()) return
        val ordered = bars.sortedBy { it.dateTime }
        remember(Key(exchange, symbol, day), ordered)
        try {
            // 그날 하루치를 통째로 갈아 끼운다. 같은 날이 두 번 들어와도 봉이 겹치지 않는다
            transaction.executeWithoutResult {
                repository.deleteDay(exchange, symbol, day)
                repository.saveAll(ordered.map { entityOf(exchange, symbol, day, it) })
            }
        } catch (e: Exception) {
            log.warn("해외 지난 날 분봉 DB 저장 실패 ({}:{}, {})", exchange, symbol, day, e)
        }
    }

    /** [before] 전의 날을 메모리와 DB에서 지운다. DB에서 지운 행 수를 돌려준다. */
    fun purge(before: LocalDate): Int {
        synchronized(days) { days.keys.removeIf { it.day < before } }
        return transaction.execute { repository.deleteBefore(before) } ?: 0
    }

    private fun recall(key: Key): List<OverseasMinuteCandle>? = synchronized(days) {
        val entry = days[key] ?: return null
        if (ticker() - entry.storedAt > TTL.toNanos()) {
            days.remove(key)
            return null
        }
        entry.bars
    }

    private fun remember(key: Key, bars: List<OverseasMinuteCandle>) = synchronized(days) {
        days.remove(key)
        days[key] = Remembered(ticker(), bars)
        while (days.size > MAX_ENTRIES) days.remove(days.keys.first())
    }

    companion object {
        /** 메모리에 두는 기간. 지나면 DB에서 다시 올린다. */
        private val TTL = Duration.ofDays(4)

        /** 메모리 상한 — 상세 화면으로 연 종목 × 며칠. */
        private const val MAX_ENTRIES = 200

        private fun OverseasMinuteCandleEntity.toCandle() = OverseasMinuteCandle(
            dateTime = minuteAt, tradingDay = tradingDay,
            open = exact(open), high = exact(high), low = exact(low), close = exact(close),
            volume = volume, tradingValue = exact(tradingValue),
        )

        private fun entityOf(exchange: String, symbol: String, day: LocalDate, c: OverseasMinuteCandle) =
            OverseasMinuteCandleEntity(
                exchange, symbol, day, c.dateTime, c.open.toFloat(), c.high.toFloat(), c.low.toFloat(), c.close.toFloat(),
                c.volume, c.tradingValue.toFloat(),
            )
    }
}
