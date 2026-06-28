package at.backend.market.application

import at.backend.library.time.TimeProvider
import at.backend.library.time.toInstantKst
import at.backend.market.domain.event.HolidayChanged
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.DayOfWeek

@Component
class MarketDayStartupHook(
    private val timeProvider: TimeProvider,
    private val eventPublisher: ApplicationEventPublisher,
) {

    private val log = KotlinLogging.logger {}

    @EventListener(ApplicationReadyEvent::class)
    fun publishMarketDay() {
        val today = timeProvider.today()
        val isHoliday = today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY
        eventPublisher.publishEvent(
            HolidayChanged(isHoliday = isHoliday, ts = timeProvider.now().toInstantKst())
        )
        log.info { "부팅 시 영업일 상태 발행 — isHoliday=$isHoliday" }
    }
}
