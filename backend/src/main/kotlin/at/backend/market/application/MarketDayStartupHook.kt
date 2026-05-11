package at.backend.market.application

import at.backend.library.time.TimeProvider
import at.backend.library.time.toInstantKst
import at.backend.market.domain.event.HolidayChanged
import at.backend.platform.kis.client.KisRealQuotationClient
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class MarketDayStartupHook(
    private val kisRealQuotationClient: KisRealQuotationClient,
    private val timeProvider: TimeProvider,
    private val eventPublisher: ApplicationEventPublisher,
) {

    private val log = KotlinLogging.logger {}

    @EventListener(ApplicationReadyEvent::class)
    fun publishMarketDay() {
        val today = timeProvider.today()
        val isHoliday = runCatching {
            // chk-holiday는 VTS 미지원이라 실거래 자격증명 클라이언트로 호출
            kisRealQuotationClient.checkHoliday(today).output.firstOrNull()?.opndYn != "Y"
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
