package judozoo.market.application

import judozoo.library.JobFailures
import judozoo.library.KST
import judozoo.market.domain.Region
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

@Component
class MarketQuoteScheduler(
    private val quotes: MarketQuotes,
    private val calendar: MarketCalendar,
    private val failures: JobFailures,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 30_000, initialDelay = 0)
    fun poll() {
        val at = LocalDateTime.now(clock.withZone(KST))
        val active: Map<String, () -> Boolean> = mapOf(
            "kospi" to { domesticOpen(at, LocalTime.of(9, 0), LocalTime.of(15, 42)) },
            "kosdaq" to { domesticOpen(at, LocalTime.of(9, 0), LocalTime.of(15, 42)) },
            "futures-kospi" to { domesticOpen(at, LocalTime.of(8, 45), LocalTime.of(15, 47)) },
            "futures-kosdaq" to { domesticOpen(at, LocalTime.of(8, 45), LocalTime.of(15, 47)) },
            "night" to { nightOpen(at) },
            MarketQuotes.NASDAQ_SYMBOL to { usCashOpen(at) },
            MarketQuotes.NASDAQ_FUTURES_SYMBOL to { globalOpen(at) },
            "KRW=X" to { globalOpen(at) },
            "CL=F" to { globalOpen(at) },
            "^VIX" to { usCashOpen(at) },
            "^TNX" to { usCashOpen(at) },
        )
        quotes.refreshers().forEach { (name, refresh) ->
            try {
                if (!refresh.run(active.getValue(name))) {
                    log.warn("시세 갱신 빈 값 ({}) — 마지막 정상값 유지", name)
                    failures.record(JOB)
                }
            } catch (e: Exception) {
                log.warn("시세 갱신 실패 ({}) — 마지막 정상값 유지", name, e)
                failures.record(JOB)
            }
        }
    }

    internal fun domesticOpen(at: LocalDateTime, start: LocalTime, end: LocalTime): Boolean =
        !at.toLocalTime().isBefore(start) && !at.toLocalTime().isAfter(end) && !calendar.isHoliday(Region.KR)

    internal fun nightOpen(at: LocalDateTime): Boolean {
        val time = at.toLocalTime()
        if (time.isBefore(LocalTime.of(18, 0)) && time.isAfter(LocalTime.of(6, 2))) return false
        val day = if (!time.isBefore(LocalTime.of(18, 0))) at.toLocalDate() else at.toLocalDate().minusDays(1)
        return calendar.isOpen(day) ?: (day.dayOfWeek != DayOfWeek.SATURDAY && day.dayOfWeek != DayOfWeek.SUNDAY)
    }

    internal fun usCashOpen(at: LocalDateTime): Boolean {
        val local = ny(at).toLocalTime()
        return !local.isBefore(LocalTime.of(9, 30)) && !local.isAfter(LocalTime.of(16, 15)) && !calendar.isHoliday(Region.US)
    }

    internal fun globalOpen(at: LocalDateTime): Boolean {
        val local = ny(at)
        val time = local.toLocalTime()
        return when (local.dayOfWeek) {
            DayOfWeek.SATURDAY -> false
            DayOfWeek.SUNDAY -> !time.isBefore(LocalTime.of(18, 0))
            DayOfWeek.FRIDAY -> !time.isAfter(LocalTime.of(17, 15))
            else -> true
        }
    }

    private fun ny(at: LocalDateTime) = at.atZone(KST).withZoneSameInstant(Region.US.zone).toLocalDateTime()

    companion object {
        const val JOB = "market-quote-poller"
    }
}
