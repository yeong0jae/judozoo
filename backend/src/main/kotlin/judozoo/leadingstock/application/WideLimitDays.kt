package judozoo.leadingstock.application

import judozoo.leadingstock.application.StockMinuteArchive.Companion.baseCode
import judozoo.leadingstock.domain.LeadingStockSnapshot
import judozoo.leadingstock.domain.WideLimitDayEntity
import judozoo.leadingstock.domain.WideLimitDayRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate
import java.time.LocalDateTime

@Component
class WideLimitDays(private val repository: WideLimitDayRepository, private val transaction: TransactionTemplate) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val lock = Any()
    private var day: LocalDate? = null
    private var codes: MutableSet<String> = mutableSetOf()

    fun mark(stocks: List<LeadingStockSnapshot>, today: LocalDate, at: LocalDateTime): List<LeadingStockSnapshot> {
        val known = today(today)
        val fresh = stocks.filter { it.exceedsNormalLimit }.map { baseCode(it.stockCode) }.toSet() - known
        if (fresh.isNotEmpty()) {
            synchronized(lock) { codes.addAll(fresh) }
            save(today, fresh, at)
        }
        val marked = synchronized(lock) { codes.toSet() }
        return stocks.map { if (baseCode(it.stockCode) in marked) it.copy(wideLimitDay = true) else it }
    }

    private fun today(today: LocalDate): Set<String> {
        synchronized(lock) { if (day == today) return codes.toSet() }
        val loaded = try {
            transaction.execute {
                repository.deleteBefore(today.minusDays(KEEP_DAYS))
                repository.codesOn(today)
            }.orEmpty()
        } catch (e: Exception) {
            log.warn("제한폭 넓은 날 기록 DB 조회 실패 — 메모리만으로 기억한다", e)
            emptySet()
        }
        return synchronized(lock) {
            if (day != today) {
                day = today
                codes = loaded.toMutableSet()
            }
            codes.toSet()
        }
    }

    private fun save(today: LocalDate, fresh: Set<String>, at: LocalDateTime) {
        try {
            transaction.executeWithoutResult {
                val missing = fresh - repository.codesOn(today)
                repository.saveAll(missing.sorted().map { WideLimitDayEntity(today, it, at) })
            }
        } catch (e: Exception) {
            log.warn("제한폭 넓은 날 기록 DB 저장 실패 ({})", fresh.sorted().joinToString(", "), e)
        }
        log.info("제한폭 넓은 날 — {}", fresh.sorted().joinToString(", "))
    }

    companion object {
        private const val KEEP_DAYS = 7L
    }
}
