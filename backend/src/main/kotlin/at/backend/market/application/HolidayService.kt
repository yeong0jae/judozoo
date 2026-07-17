package at.backend.market.application

import at.backend.library.time.TimeProvider
import at.backend.platform.kis.client.KisHolidayClient
import org.springframework.stereotype.Service
import java.time.LocalDate

/**
 * 국내 개장일 판정 — KIS 휴장일 조회를 하루 한 번만 부르고 메모리에 캐시한다(원장 연동 API라 잦은 호출 금지).
 * 기준일을 20일 과거로 잡아 최근 개장일 집합을 받으므로, 최근 10거래일 판정에 충분하다.
 */
@Service
class HolidayService(
    private val client: KisHolidayClient,
    private val timeProvider: TimeProvider,
) {
    @Volatile private var cachedDay: LocalDate? = null
    @Volatile private var openDays: Set<LocalDate> = emptySet()

    /** [date]가 주식시장 개장일인지. 휴장일 목록을 못 받았으면(빈 집합) 판정 불가로 보고 null. */
    fun isOpen(date: LocalDate): Boolean? {
        val days = refreshedOpenDays()
        if (days.isEmpty()) return null
        return date in days
    }

    private fun refreshedOpenDays(): Set<LocalDate> {
        val today = timeProvider.today()
        if (cachedDay != today || openDays.isEmpty()) {
            val fetched = client.fetchOpenDays(today.minusDays(LOOKBACK_DAYS))
            if (fetched.isNotEmpty()) {
                openDays = fetched
                cachedDay = today
            }
        }
        return openDays
    }

    companion object {
        private const val LOOKBACK_DAYS = 20L
    }
}
