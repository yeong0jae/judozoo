package at.backend.market.application

import at.backend.market.domain.regime.RegimeDailyRecord
import at.backend.market.infrastructure.repository.RegimeDailyRecordJpaRepository
import at.backend.platform.kiwoom.client.KiwoomMarketClient
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalTime

/**
 * [임시] 시장 흐름 최근 10일 부트스트랩 시드.
 *
 * 신규 기능이라 과거 데이터가 없어, 최초 1회 삼성전자(005930) 가격으로 차트를 채운다.
 * 08:15 NXT 과거값은 조회 불가라 시가(09:00)를 아침 NXT 대용으로 근사한다.
 * 운영 데이터가 쌓이면(테이블 비어있지 않으면) 실행하지 않는다. 시드 확인 후 제거 예정.
 */
@Component
@Profile("!test")
class RegimeBootstrapSeeder(
    private val marketClient: KiwoomMarketClient,
    private val dailyRepository: RegimeDailyRecordJpaRepository,
) {
    private val log = KotlinLogging.logger {}

    @EventListener(ApplicationReadyEvent::class)
    fun seed() {
        if (dailyRepository.count() >= 20) return // 20일 채워졌으면 시드하지 않음
        runCatching { doSeed() }.onFailure { log.warn(it) { "레짐 부트스트랩 시드 실패" } }
    }

    private fun doSeed() {
        val code = "005930" // 삼성전자
        val today = LocalDate.now()
        val daily = marketClient.fetchDailyCandles(code, 40).sortedBy { it.date }
        if (daily.size < 2) {
            log.warn { "레짐 시드: 일봉 부족(${daily.size}) — 생략" }
            return
        }
        var seeded = 0
        for (i in daily.indices.drop(1).takeLast(20)) {
            val day = daily[i]
            val prevClose = daily[i - 1].closePrice
            val open = day.openPrice
            if (prevClose <= 0 || open <= 0) continue
            if (!day.date.isBefore(today) || dailyRepository.existsById(day.date)) continue

            val gap1 = (open - prevClose).toDouble() / prevClose * 100 // 전일종가→시가 (≈오전 NXT)
            val gap2Close = (day.closePrice - open).toDouble() / open * 100 // 시가→종가
            val gap2High = (day.highPrice - open).toDouble() / open * 100
            val gap2Low = (day.lowPrice - open).toDouble() / open * 100
            val p1000 = marketClient.fetchMinuteCandles(code, day.date)
                .filter { it.dateTime.toLocalDate() == day.date && it.dateTime.toLocalTime() >= TEN }
                .minByOrNull { it.dateTime }
                ?.closePrice
            val gap2At1000 = if (p1000 != null && p1000 > 0) (p1000 - open).toDouble() / open * 100 else null

            dailyRepository.save(
                RegimeDailyRecord(
                    date = day.date,
                    gap1 = gap1,
                    gap2Close = gap2Close,
                    gap2High = gap2High,
                    gap2Low = gap2Low,
                    gap2At1000 = gap2At1000,
                    gap2At1530 = gap2Close, // 시드: 종가 = 15:30 (과거 NXT 애프터마켓 없음 → 20:00은 null)
                ),
            )
            seeded++
        }
        log.info { "레짐 부트스트랩 시드 완료 — 삼성전자 ${seeded}일 (08:15은 시가 근사)" }
    }

    companion object {
        private val TEN = LocalTime.of(10, 0)
    }
}
