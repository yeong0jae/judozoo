package at.backend.market.application

import at.backend.library.time.TimeProvider
import at.backend.platform.toss.client.TossMarketCalendarClient
import org.springframework.stereotype.Service
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * 시장 휴장 판정 — 토스 장 운영 정보 기반. 배너(국내/해외) 공용.
 * 토스 호출을 (지역, 날짜)당 하루 한 번만 하고 캐시한다. 조회 실패(null)면 주말만으로 폴백.
 * '오늘'은 지역의 현지 날짜다: KR은 KST, US는 미 동부시각 기준.
 */
@Service
class MarketCalendarService(
    private val client: TossMarketCalendarClient,
    private val timeProvider: TimeProvider,
) {
    private val cache = ConcurrentHashMap<Region, Cached>()

    /** [region] 시장의 오늘이 휴장(주말·공휴일)인지. */
    fun isHoliday(region: Region): Boolean {
        val today = region.today(timeProvider)
        cache[region]?.let { if (it.date == today) return it.isHoliday }

        val open = client.isTradingDay(region.code, today)
        val isHoliday = open?.not() ?: today.isWeekend() // 조회 실패 시 주말 폴백
        cache[region] = Cached(today, isHoliday)
        return isHoliday
    }

    private data class Cached(val date: LocalDate, val isHoliday: Boolean)

    enum class Region(val code: String, private val zone: ZoneId) {
        KR("KR", ZoneId.of("Asia/Seoul")),
        US("US", ZoneId.of("America/New_York"));

        fun today(timeProvider: TimeProvider): LocalDate =
            timeProvider.now().atZone(KST).withZoneSameInstant(zone).toLocalDate()

        companion object {
            private val KST = ZoneId.of("Asia/Seoul")
        }
    }

    companion object {
        private fun LocalDate.isWeekend() =
            dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY
    }
}
