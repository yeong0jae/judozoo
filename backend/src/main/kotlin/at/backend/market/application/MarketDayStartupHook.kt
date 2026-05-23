package at.backend.market.application

import at.backend.library.time.TimeProvider
import at.backend.library.time.toInstantKst
import at.backend.market.domain.event.HolidayChanged
import at.backend.trading.application.broker.BrokerTradingClient
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class MarketDayStartupHook(
    private val broker: BrokerTradingClient,
    private val timeProvider: TimeProvider,
    private val eventPublisher: ApplicationEventPublisher,
) {

    private val log = KotlinLogging.logger {}

    @EventListener(ApplicationReadyEvent::class)
    fun publishMarketDay() {
        val today = timeProvider.today()
        val isHoliday = runCatching {
            !broker.isMarketOpen(today)
        }.getOrElse {
            log.warn(it) { "개장일 검증 실패 — HolidayChanged 발행 생략" }
            return
        }
        eventPublisher.publishEvent(
            HolidayChanged(isHoliday = isHoliday, ts = timeProvider.now().toInstantKst())
        )
        log.info { "부팅 시 영업일 상태 발행 — isHoliday=$isHoliday" }
    }
}
