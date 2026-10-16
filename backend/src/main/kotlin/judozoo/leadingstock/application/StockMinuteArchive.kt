package judozoo.leadingstock.application

import judozoo.leadingstock.domain.MinuteCandle
import judozoo.leadingstock.domain.StockMinuteCandleEntity
import judozoo.leadingstock.domain.StockMinuteCandleRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.LocalDate

/**
 * 지난 거래일 종목 1분봉 보관소 — `(종목, 날짜)` → 그날 봉 전체.
 *
 * 키움 과거 분봉은 기준일 단위 페이지라, 날짜 단위로 넣어 두지 않으면 아침마다 감시 풀 전체의 어제 봉을 다시 받는다.
 *
 * **완성된 날만 넣는다.** 형성 중인 날을 넣으면 4일 동안 그대로 굳는다.
 * - 마감 확정(20:01) 뒤의 당일 저장소
 * - 키움 페이지에서 가장 이른 날을 뺀 나머지(가장 이른 날은 페이지 끝에서 잘려 있다)
 *
 * **메모리 앞, DB(`stock_minute_candle`) 뒤.** DB가 죽어 있으면 메모리만으로 동작한다.
 * 하루치를 통째로 갈아 끼우므로 같은 날이 두 번 들어와도 결과가 같다. 2주 지난 날은 마감 확정(20:01)이 지운다([purge]).
 */
@Component
class StockMinuteArchive(
    private val repository: StockMinuteCandleRepository,
    private val transaction: TransactionTemplate,
    private val ticker: () -> Long = System::nanoTime,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private class Remembered(val storedAt: Long, val bars: List<MinuteCandle>)

    private val days = LinkedHashMap<Pair<String, LocalDate>, Remembered>()

    /** 그날 봉 전체(시각 오름차순). 메모리에 없으면 DB에서 올린다. 어디에도 없으면 null. */
    fun get(stockCode: String, day: LocalDate): List<MinuteCandle>? {
        val key = baseCode(stockCode) to day
        recall(key)?.let { return it }
        val bars = try {
            repository.findByStockCodeAndTradeDateOrderByMinuteAt(key.first, day).map { it.toCandle() }
        } catch (e: Exception) {
            log.warn("지난 날 분봉 DB 조회 실패 stk_cd={} day={}", key.first, day, e)
            return null
        }
        if (bars.isEmpty()) return null
        remember(key, bars)
        return bars
    }

    /** 완성된 하루치를 넣는다. 비어 있으면 넣지 않는다 — 없는 날과 못 받은 날을 섞지 않는다. */
    fun put(stockCode: String, day: LocalDate, bars: List<MinuteCandle>) {
        if (bars.isEmpty()) return
        val code = baseCode(stockCode)
        val ordered = bars.sortedBy { it.dateTime }
        remember(code to day, ordered)
        try {
            // 그날 하루치를 통째로 갈아 끼운다. 같은 날이 두 번 들어와도 봉이 겹치지 않는다
            transaction.executeWithoutResult {
                repository.deleteDay(code, day)
                repository.saveAll(ordered.map { StockMinuteCandleEntity.of(code, day, it) })
            }
        } catch (e: Exception) {
            log.warn("지난 날 분봉 DB 저장 실패 stk_cd={} day={}", code, day, e)
        }
    }

    /** [before] 전의 날을 메모리와 DB에서 지운다. DB에서 지운 행 수를 돌려준다. */
    fun purge(before: LocalDate): Int {
        synchronized(days) { days.keys.removeIf { it.second < before } }
        return transaction.execute { repository.deleteBefore(before) } ?: 0
    }

    /** 키움 페이지에서 완성된 날만 골라 넣는다. 가장 이른 날은 페이지 끝에서 잘려 있어 뺀다. */
    fun putPage(stockCode: String, page: List<MinuteCandle>) {
        val byDay = page.groupBy { it.dateTime.toLocalDate() }
        if (byDay.isEmpty()) return
        val cut = byDay.keys.min()
        byDay.filterKeys { it != cut }.forEach { (day, bars) -> put(stockCode, day, bars) }
    }

    private fun recall(key: Pair<String, LocalDate>): List<MinuteCandle>? = synchronized(days) {
        val entry = days[key] ?: return null
        if (ticker() - entry.storedAt > TTL.toNanos()) {
            days.remove(key)
            return null
        }
        entry.bars
    }

    private fun remember(key: Pair<String, LocalDate>, bars: List<MinuteCandle>) = synchronized(days) {
        days.remove(key)
        days[key] = Remembered(ticker(), bars)
        while (days.size > MAX_ENTRIES) days.remove(days.keys.first())
    }

    companion object {
        /** 메모리에 두는 기간. 지나면 DB에서 다시 올린다. */
        private val TTL = Duration.ofDays(4)

        /** 메모리 상한 — 감시 풀(~35종목) × 며칠 + 상세 화면으로 연 종목. 넘치면 오래 넣은 것부터 밀어낸다. */
        internal const val MAX_ENTRIES = 300

        /** 랭킹 코드는 `_AL`이 붙어 오고 상세 화면은 맨 코드로 온다 — 같은 종목으로 본다. */
        fun baseCode(stockCode: String) = stockCode.substringBefore("_")
    }
}
