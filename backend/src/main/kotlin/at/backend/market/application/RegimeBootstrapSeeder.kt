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
 * NXT 분봉(_NX)으로 08:15(아침 NXT)·20:00(애프터마켓)을, KRX로 10:00을 가져온다.
 * 아침 NXT(gap1)는 **전일 20:00(NXT 마감) 기준**. 과거(date<today) 행만 매 부팅 시 덮어쓴다.
 * 내일 실데이터 전환 시 제거 예정.
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
        var prevP2000: Long? = null // 직전 거래일 NXT 20:00 종가 (다음날 아침 NXT 기준)
        for (i in daily.indices.drop(1).takeLast(20)) {
            val day = daily[i]
            val prevClose = daily[i - 1].closePrice
            val open = day.openPrice
            if (prevClose <= 0 || open <= 0) continue

            val nxMinutes = marketClient.fetchMinuteCandles("${code}_NX", day.date)
                .filter { it.dateTime.toLocalDate() == day.date }
            val p0815 = nxMinutes.filter { it.dateTime.toLocalTime() >= NXT_OPEN }.minByOrNull { it.dateTime }?.closePrice
            val p2000 = nxMinutes.filter { it.dateTime.toLocalTime() >= AFTER }.maxByOrNull { it.dateTime }?.closePrice
            val p1000 = marketClient.fetchMinuteCandles(code, day.date)
                .filter { it.dateTime.toLocalDate() == day.date && it.dateTime.toLocalTime() >= TEN }
                .minByOrNull { it.dateTime }?.closePrice

            val anchor = p0815?.takeIf { it > 0 } ?: open // 08:15 NXT, 없으면 시가
            val base = prevP2000?.takeIf { it > 0 } ?: prevClose // 전일 20:00, 없으면 15:30
            prevP2000 = p2000 // 다음 반복용
            if (!day.date.isBefore(today)) continue // 오늘/미래(실데이터)는 시드 안 함

            val pct = { p: Long -> (p - anchor).toDouble() / anchor * 100 }
            dailyRepository.save(
                RegimeDailyRecord(
                    date = day.date,
                    gap1 = (anchor - base).toDouble() / base * 100, // 전일 20:00 → 08:15
                    gap2Close = (p2000?.takeIf { it > 0 } ?: day.closePrice).let { pct(it) },
                    gap2High = pct(day.highPrice),
                    gap2Low = pct(day.lowPrice),
                    gap2At1000 = p1000?.takeIf { it > 0 }?.let { pct(it) },
                    gap2At1530 = pct(day.closePrice),
                    gap2At2000 = p2000?.takeIf { it > 0 }?.let { pct(it) },
                ),
            )
            seeded++
        }
        log.info { "레짐 부트스트랩 시드 완료 — 삼성전자 ${seeded}일 (아침 NXT=전일 20:00 기준)" }
    }

    companion object {
        private val NXT_OPEN = LocalTime.of(8, 15)
        private val TEN = LocalTime.of(10, 0)
        private val AFTER = LocalTime.of(15, 40)
    }
}
