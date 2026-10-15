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

/**
 * 휴장 판정 — 국내 개장일(KIS) / 지역별 휴장(토스) / 장 상태.
 *
 * 호출 빈도를 아끼려고 둘 다 하루 한 번만 외부를 두드리고 메모리에 캐시한다.
 */
@Service
class MarketCalendar(
    private val kisHoliday: KisHoliday,
    private val toss: TossMarketCalendar,
    private val clock: Clock,
    private val ticker: () -> Long = System::nanoTime,
) {
    @Volatile private var openDaysOf: LocalDate? = null
    @Volatile private var openDays: Set<LocalDate> = emptySet()

    /** 지역 → (현지 날짜, 휴장 여부, 다시 물을 시각). 다시 물을 시각은 폴백일 때만 있다. */
    private data class Cached(val day: LocalDate, val holiday: Boolean, val retryAt: Long?)

    private val holidays = ConcurrentHashMap<Region, Cached>()
    private val holidayLock = Any()

    /**
     * [on]이 주식시장 개장일인지. 휴장일 목록을 못 받았으면 **판정 불가로 보고 null** —
     * false로 단정하면 멀쩡한 거래일 데이터가 화면에서 사라진다.
     */
    fun isOpen(on: LocalDate): Boolean? = refreshedOpenDays().takeIf { it.isNotEmpty() }?.let { on in it }

    /**
     * [on] 전날까지 중 마지막 개장일. 목록을 못 받았으면 null — 날짜를 지어내지 않는다.
     * 주말만 건너뛰면 추석 같은 연휴 평일을 거래일로 적는다. 목록이 기준일 20일 전부터 오므로 그 안의 연휴는 다 건너뛴다.
     */
    fun previousOpenDay(on: LocalDate): LocalDate? = refreshedOpenDays().filter { it < on }.maxOrNull()

    /** 알고 있는 개장일 전부(기준일 20일 전부터 한 응답 약 24일치). 못 받았으면 빈 집합. */
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

    /**
     * [region] 시장의 오늘이 휴장(주말·공휴일)인지. 조회 실패면 주말 폴백.
     *
     * **한 번만 묻는다.** 날짜가 바뀌거나 재시작한 직후엔 폴러·화면 요청이 한꺼번에 여기로 온다.
     * 토스 장 운영 정보는 초당 3건이라, 각자 부르면 한도에 걸려 전부 실패한다.
     * **실패는 하루 내내 들고 있지 않는다.** 주말 폴백을 그날 치로 캐시하면 추석 같은 평일 휴장을 하루 종일 개장으로 본다 — 몇 분 뒤 다시 묻는다.
     */
    fun isHoliday(region: Region): Boolean {
        val today = region.today(clock.instant())
        cachedHoliday(region, today)?.let { return it }
        // 락을 쥔 채 부른다 — 뒤따라온 스레드는 기다렸다가 캐시된 답을 받는다. 하루 한두 번이라 짧다
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

    /** (휴장 여부, 거래시간 내 여부). NXT 프리마켓 08:00부터 애프터마켓 20:00까지. */
    fun marketStatus(): Pair<Boolean, Boolean> {
        val now = LocalTime.now(clock.withZone(KST))
        return isHoliday(Region.KR) to (!now.isBefore(TRADING_START) && !now.isAfter(TRADING_END))
    }

    /**
     * (미국 휴장 여부, 미 동부 04:00~20:00 안인지). 서머타임은 시간대가 알아서 반영한다.
     * 프리마켓부터 애프터마켓 마감까지 — 화면 세션(`lib/marketSession.ts`)과 같다.
     */
    fun usMarketStatus(): Pair<Boolean, Boolean> {
        val now = clock.instant().atZone(Region.US.zone).toLocalTime()
        return isHoliday(Region.US) to (!now.isBefore(US_TRADING_START) && now.isBefore(US_TRADING_END))
    }

    /** 다음 미 동부 04:00까지 남은 시간. 서머타임 전환일에도 실제 경과 시간으로 잰다. */
    fun untilUsSession(): Duration {
        val at = clock.instant().atZone(Region.US.zone)
        val day = if (at.toLocalTime().isBefore(US_TRADING_START)) at.toLocalDate() else at.toLocalDate().plusDays(1)
        return Duration.between(at.toInstant(), day.atTime(US_TRADING_START).atZone(Region.US.zone).toInstant())
    }

    companion object {
        /** 기준일을 20일 과거로 잡아 최근 10거래일 판정에 충분한 집합을 받는다. */
        private const val LOOKBACK_DAYS = 20L

        /** 조회에 실패해 주말로만 판정한 값은 이만큼만 믿고 다시 묻는다. */
        val RETRY_AFTER_FAILURE: Duration = Duration.ofMinutes(5)

        private val TRADING_START = LocalTime.of(8, 0)
        private val TRADING_END = LocalTime.of(20, 0)
        private val US_TRADING_START = LocalTime.of(4, 0)
        private val US_TRADING_END = LocalTime.of(20, 0)
    }
}
