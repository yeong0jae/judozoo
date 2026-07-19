package at.backend.market.application

import at.backend.library.time.TimeProvider
import at.backend.platform.kiwoom.client.KiwoomProgramClient
import at.backend.stock.domain.Market
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalTime

/**
 * 장중 주기적으로 코스피·코스닥 프로그램 매매의 당일 누적(ka90010 오늘 행)을 스냅샷으로 적재한다.
 * 세션(오전/오후/막판) 순매수는 이 스냅샷들의 경계 diff로 계산된다. 휴장/장 밖이면 스킵. 테스트에선 제외.
 */
@Component
@Profile("!test")
class ProgramTradePoller(
    private val client: KiwoomProgramClient,
    private val service: MarketProgramService,
    private val marketStatusService: MarketStatusService,
    private val timeProvider: TimeProvider,
) {
    private val log = KotlinLogging.logger {}

    @Scheduled(fixedDelayString = "\${market.program.poll-interval-millis:120000}")
    fun onSchedule() {
        if (marketStatusService.getStatus().isHoliday) return
        val time = timeProvider.now().toLocalTime()
        if (time < SNAPSHOT_START || time > SNAPSHOT_END) return
        val today = timeProvider.today()
        val now = timeProvider.now()
        Market.entries.forEach { market ->
            val todayPoint = client.fetchMarketProgramDaily(market, today).firstOrNull { it.date == today } ?: return@forEach
            runCatching { service.record(market, today, now, todayPoint) }
                .onFailure { log.warn(it) { "프로그램 스냅샷 적재 실패 ($market)" } }
        }
    }

    companion object {
        private val SNAPSHOT_START: LocalTime = LocalTime.of(8, 0)
        private val SNAPSHOT_END: LocalTime = LocalTime.of(20, 0)
    }
}
