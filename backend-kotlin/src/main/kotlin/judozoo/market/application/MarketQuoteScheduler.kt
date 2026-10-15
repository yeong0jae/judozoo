package judozoo.market.application

import judozoo.library.KST
import judozoo.market.domain.Region
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 시세 폴러(30초) — 요청자 수와 무관하게 시세를 채운다. 개장 전에는 마지막 정상값을 계속 제공한다.
 *
 * 공존하는 동안(028) Python도 같은 폴러를 돌린다 — 메모리 시세는 프로세스마다 따로라 각자 채운다.
 * **선물 투자자 스냅샷 폴러(DB 쓰기)는 Python에만 있다** — 종가베팅 등 Python 기능이 그 스냅샷을 읽는다.
 */
@Component
class MarketQuoteScheduler(
    private val quotes: MarketQuotes,
    private val calendar: MarketCalendar,
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
                // 재기동 직후엔 장 밖이어도 한 번 채운다. 빈 값은 다음 회차에 재시도한다
                if (!refresh.run(active.getValue(name))) log.warn("시세 갱신 빈 값 ({}) — 마지막 정상값 유지", name)
            } catch (e: Exception) {
                log.warn("시세 갱신 실패 ({}) — 마지막 정상값 유지", name, e)
            }
        }
    }

    internal fun domesticOpen(at: LocalDateTime, start: LocalTime, end: LocalTime): Boolean =
        !at.toLocalTime().isBefore(start) && !at.toLocalTime().isAfter(end) && !calendar.isHoliday(Region.KR)

    /** 자정 뒤에도 전날 시작한 세션이다. 금요일 밤→토요일 새벽을 포함한다. */
    internal fun nightOpen(at: LocalDateTime): Boolean {
        val time = at.toLocalTime()
        if (time.isBefore(LocalTime.of(18, 0)) && time.isAfter(LocalTime.of(6, 2))) return false
        val day = if (!time.isBefore(LocalTime.of(18, 0))) at.toLocalDate() else at.toLocalDate().minusDays(1)
        return calendar.isOpen(day) ?: (day.dayOfWeek != DayOfWeek.SATURDAY && day.dayOfWeek != DayOfWeek.SUNDAY)
    }

    /** 지연 시세의 마지막 값도 받도록 정규장 마감 뒤 15분을 더 갱신한다. */
    internal fun usCashOpen(at: LocalDateTime): Boolean {
        val local = ny(at).toLocalTime()
        return !local.isBefore(LocalTime.of(9, 30)) && !local.isAfter(LocalTime.of(16, 15)) && !calendar.isHoliday(Region.US)
    }

    /** 선물·FX는 주식 휴장일과 다르다. 주말은 쉬고 조기 마감·상품별 휴일은 공급자 값을 따른다. */
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
}
