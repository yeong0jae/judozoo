package at.backend.theme.application

import at.backend.market.application.MarketStatusService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@Profile("!test")
class ThemeCapturePoller(
    private val service: ThemeCalendarService,
    private val marketStatusService: MarketStatusService,
) {
    private val log = KotlinLogging.logger {}

    @Scheduled(cron = "0 40 15 * * *", zone = "Asia/Seoul")
    fun onSchedule() {
        if (marketStatusService.getStatus().isHoliday) {
            log.info { "휴장일 — 테마 캡처 스킵" }
            return
        }
        runCatching { service.capture() }.onFailure { log.warn(it) { "테마 캡처 실패" } }
    }
}
