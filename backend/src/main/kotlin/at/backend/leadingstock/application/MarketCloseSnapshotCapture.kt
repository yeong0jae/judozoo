package at.backend.leadingstock.application

import at.backend.library.time.TimeProvider
import at.backend.market.application.MarketStatusService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 매 거래일 15:40에 코스피·코스닥 마감 투자자 순매수를 스냅샷으로 한 번 적재한다.
 * 정규장 마감(15:30) + 종가 동시호가 정산 여유 10분. 휴장이면 스킵, 멱등이라 중복 캡처는 무시. 테스트 제외.
 */
@Component
@Profile("!test")
class MarketCloseSnapshotCapture(
    private val snapshotService: MarketCloseSnapshotService,
    private val marketStatusService: MarketStatusService,
    private val timeProvider: TimeProvider,
) {
    private val log = KotlinLogging.logger {}

    @Scheduled(cron = "0 40 15 * * MON-FRI", zone = "Asia/Seoul")
    fun onClose() {
        if (marketStatusService.getStatus().isHoliday) return
        runCatching {
            val saved = snapshotService.capture(timeProvider.today(), timeProvider.now())
            if (saved > 0) log.info { "마감 스냅샷 ${saved}건 적재" }
        }.onFailure { log.warn(it) { "마감 스냅샷 적재 실패" } }
    }
}
