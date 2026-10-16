package judozoo.market.application

import judozoo.library.KST
import judozoo.market.domain.Region
import judozoo.platform.kis.KisHoliday
import judozoo.platform.toss.TossMarketCalendar
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap

@Service
class MarketCalendar(
    private val kisHoliday: KisHoliday,
    private val toss: TossMarketCalendar,
    private val clock: Clock,
    private val ticker: () -> Long = System::nanoTime,
) {
    @Volatile private var openDaysOf: LocalDate? = null
    @Volatile private var openDays: Set<LocalDate> = emptySet()

    private data class Cached(val day: LocalDate, val holiday: Boolean, val retryAt: Long?)

    private val holidays = ConcurrentHashMap<Region, Cached>()
    private val holidayLock = Any()

    fun isOpen(on: LocalDate): Boolean? = refreshedOpenDays().takeIf { it.isNotEmpty() }?.let { on in it }

    fun previousOpenDay(on: LocalDate): LocalDate? = refreshedOpenDays().filter { it < on }.maxOrNull()

    fun openDays(): Set<LocalDate> = refreshedOpenDays()

    private fun refreshedOpenDays(): Set<LocalDate> {
        val today = LocalDate.now(clock.withZone(KST))
        if (openDaysOf == today && openDays.isNotEmpty()) return openDays
        synchronized(this) {
            if (openDaysOf == today && openDays.isNotEmpty()) return openDays
            val fetched = kisHoliday.fetchOpenDays(today.minusDays(LOOKBACK_DAYS))
            if (fetched.isNotEmpty()) {
                openDays = fetched
                openDaysOf = today
            }
            return openDays
        }
    }

    fun isHoliday(region: Region): Boolean {
        val today = region.today(clock.instant())
        cachedHoliday(region, today)?.let { return it }
        synchronized(holidayLock) {
            cachedHoliday(region, today)?.let { return it }
            val open = toss.isTradingDay(region.name, today)
            val holiday = open?.not() ?: (today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY)
            holidays[region] = Cached(today, holiday, if (open == null) ticker() + RETRY_AFTER_FAILURE.toNanos() else null)
            return holiday
        }
    }

    private fun cachedHoliday(region: Region, today: LocalDate): Boolean? {
        val cached = holidays[region] ?: return null
        if (cached.day != today) return null
        if (cached.retryAt != null && ticker() >= cached.retryAt) return null
        return cached.holiday
    }

    fun marketStatus(): Pair<Boolean, Boolean> {
        val now = LocalTime.now(clock.withZone(KST))
        return isHoliday(Region.KR) to (!now.isBefore(TRADING_START) && !now.isAfter(TRADING_END))
    }

    fun usMarketStatus(): Pair<Boolean, Boolean> {
        val now = clock.instant().atZone(Region.US.zone).toLocalTime()
        return isHoliday(Region.US) to (!now.isBefore(US_TRADING_START) && now.isBefore(US_TRADING_END))
    }

    fun untilUsSession(): Duration {
        val at = clock.instant().atZone(Region.US.zone)
        val day = if (at.toLocalTime().isBefore(US_TRADING_START)) at.toLocalDate() else at.toLocalDate().plusDays(1)
        return Duration.between(at.toInstant(), day.atTime(US_TRADING_START).atZone(Region.US.zone).toInstant())
    }

    companion object {
        private const val LOOKBACK_DAYS = 20L

        val RETRY_AFTER_FAILURE: Duration = Duration.ofMinutes(5)

        private val TRADING_START = LocalTime.of(8, 0)
        private val TRADING_END = LocalTime.of(20, 0)
        private val US_TRADING_START = LocalTime.of(4, 0)
        private val US_TRADING_END = LocalTime.of(20, 0)
    }
}
