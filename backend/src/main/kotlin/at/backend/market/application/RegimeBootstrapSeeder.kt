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
 * [임시] 시장 흐름 최근 20일 부트스트랩 시드 — 삼성전자(005930) 가격으로 4구간을 채운다.
 *
 * 08:15 NXT 과거값이 없어 시가(09:00)를 아침 NXT 대용으로 근사한다. 과거(date<today) 행만
 * 매 부팅 시 덮어쓴다(오늘/미래·실데이터는 건드리지 않음). 내일 실데이터 수집 전환 시 제거 예정.
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
            if (prevClose <= 0 || open <= 0 || !day.date.isBefore(today)) continue

            val pct = { p: Long -> (p - open).toDouble() / open * 100 }
            val gap1 = (open - prevClose).toDouble() / prevClose * 100 // 전일종가→시가 (≈오전 NXT)
            val gap2At1530 = pct(day.closePrice) // 시가→종가(15:30)

            val minutes = marketClient.fetchMinuteCandles(code, day.date)
                .filter { it.dateTime.toLocalDate() == day.date }
            val p1000 = minutes.filter { it.dateTime.toLocalTime() >= TEN }.minByOrNull { it.dateTime }?.closePrice
            val p2000 = minutes.filter { it.dateTime.toLocalTime() >= AFTER }.maxByOrNull { it.dateTime }?.closePrice
            val gap2At1000 = p1000?.takeIf { it > 0 }?.let { pct(it) }
            val gap2At2000 = p2000?.takeIf { it > 0 }?.let { pct(it) }

            dailyRepository.save(
                RegimeDailyRecord(
                    date = day.date,
                    gap1 = gap1,
                    gap2Close = gap2At2000 ?: gap2At1530,
                    gap2High = pct(day.highPrice),
                    gap2Low = pct(day.lowPrice),
                    gap2At1000 = gap2At1000,
                    gap2At1530 = gap2At1530,
                    gap2At2000 = gap2At2000,
                ),
            )
            seeded++
        }
        log.info { "레짐 부트스트랩 시드 완료 — 삼성전자 ${seeded}일 (08:15은 시가 근사)" }
    }

    companion object {
        private val TEN = LocalTime.of(10, 0)
        private val AFTER = LocalTime.of(15, 40) // 정규장 마감 이후(NXT 애프터마켓) 마지막 체결 ≈ 20:00
    }
}
