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

/**
 * 제한폭이 넓은 날(상장 첫날) 표시 — 그날 한 번이라도 +30%를 넘은 종목을 기억한다.
 *
 * 지금 등락률만 보면 밀려 내려온 신규상장 종목이 "+26% 급등주"로 순위가 튄다. 그래서 그날 내내 환산한다.
 *
 * **메모리 앞, DB(`wide_limit_day`) 뒤** — 재시작해도 잃지 않는다. DB가 죽어 있으면 메모리만으로 동작한다.
 */
@Component
class WideLimitDays(private val repository: WideLimitDayRepository, private val transaction: TransactionTemplate) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val lock = Any()
    private var day: LocalDate? = null
    private var codes: MutableSet<String> = mutableSetOf()

    /** 지금 +30%를 넘은 종목을 기억하고, 오늘 넘은 적이 있는 종목에 표시를 붙여 돌려준다. */
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
        /** 지난 날 기록은 쓸 데가 없다 — 날이 바뀌어 처음 올릴 때 이보다 오래된 행을 지운다. */
        private const val KEEP_DAYS = 7L
    }
}
