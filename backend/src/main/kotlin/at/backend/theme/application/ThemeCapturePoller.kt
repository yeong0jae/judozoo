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

    // 정규장 마감(15:40)과 NXT 애프터마켓 마감(20:00) 두 번 캡처 — 20:00이 확장세션까지 반영해 그날 스냅샷을 교체한다.
    @Scheduled(cron = "0 40 15 * * *", zone = "Asia/Seoul")
    fun onRegularClose() = runCapture()

    @Scheduled(cron = "0 0 20 * * *", zone = "Asia/Seoul")
    fun onAfterMarketClose() = runCapture()

    private fun runCapture() {
        if (marketStatusService.getStatus().isHoliday) {
            log.info { "휴장일 — 테마 캡처 스킵" }
            return
        }
        runCatching { service.capture() }.onFailure { log.warn(it) { "테마 캡처 실패" } }
    }
}
