package at.backend.theme.application

import at.backend.library.time.TimeProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.DayOfWeek

@Component
@Profile("!test")
class ThemeCapturePoller(
    private val service: ThemeCalendarService,
    private val timeProvider: TimeProvider,
) {
    private val log = KotlinLogging.logger {}

    @Scheduled(cron = "0 40 15 * * *", zone = "Asia/Seoul")
    fun onSchedule() {
        val today = timeProvider.today()
        if (today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY) {
            log.info { "휴장일 — 테마 캡처 스킵" }
            return
        }
        runCatching { service.capture() }.onFailure { log.warn(it) { "테마 캡처 실패" } }
    }
}
