package at.backend.theme.application

import at.backend.library.time.TimeProvider
import at.backend.trading.application.broker.BrokerTradingClient
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 매 거래일 정규장 마감 직후 그날 강한 테마를 1회 캡처한다.
 * 휴장일/주말은 스킵. 테스트 컨텍스트에서는 네트워크를 때리지 않도록 제외.
 */
@Component
@Profile("!test")
class ThemeCapturePoller(
    private val service: ThemeCalendarService,
    private val broker: BrokerTradingClient,
    private val timeProvider: TimeProvider,
) {
    private val log = KotlinLogging.logger {}

    @Scheduled(cron = "0 40 15 * * *", zone = "Asia/Seoul")
    fun onSchedule() {
        if (!broker.isMarketOpen(timeProvider.today())) {
            log.info { "휴장일 — 테마 캡처 스킵" }
            return
        }
        runCatching { service.capture() }.onFailure { log.warn(it) { "테마 캡처 실패" } }
    }
}
