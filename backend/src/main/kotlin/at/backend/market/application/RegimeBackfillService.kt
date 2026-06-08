package at.backend.market.application

import at.backend.leadingstock.domain.MinuteCandle
import at.backend.market.domain.regime.RegimeDailyRecord
import at.backend.market.infrastructure.repository.RegimeDailyRecordJpaRepository
import at.backend.platform.kiwoom.client.KiwoomMarketClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * DB가 비어 있을 때 단일 종목 분봉을 기준으로 과거 N일치 regime_daily를 채운다.
 * gap1 adjustment(전일 14:00→20:00)는 oldest→newest 순으로 처리하면서 순차 적용.
 * 공휴일은 건너뛰지 않지만 해당일 08:15 캔들이 없으면 자동 skip된다.
 */
@Service
class RegimeBackfillService(
    private val marketClient: KiwoomMarketClient,
    private val dailyRepository: RegimeDailyRecordJpaRepository,
    private val props: RegimeProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun backfill(stockCode: String? = null, days: Int? = null) {
        val code = stockCode ?: props.backfillStockCode
        val n = days ?: props.backfillDays
        val tradingDays = recentTradingDays(n)

        val closePriceByDate = marketClient.fetchDailyCandles(code, n + 10)
            .associate { it.date to it.closePrice }

        var prevGap2At1400: Double? = null
        var prevGap2At2000: Double? = null

        tradingDays.forEach { date ->
            val existing = dailyRepository.findById(date).orElse(null)

            // 체크포인트가 모두 채워진 레코드는 skip
            if (existing != null &&
                existing.gap2At1100 != null && existing.gap2At1400 != null && existing.gap2At2000 != null
            ) {
                log.info("backfill: {} already complete, skipping", date)
                prevGap2At1400 = existing.gap2At1400
                prevGap2At2000 = existing.gap2At2000
                return@forEach
            }

            val nxtCandles = marketClient.fetchMinuteCandles("${code}_NX", date)
                .filter { it.dateTime.toLocalDate() == date }
                .sortedBy { it.dateTime }
            val regularCandles = marketClient.fetchMinuteCandles(code, date)
                .filter { it.dateTime.toLocalDate() == date }
                .sortedBy { it.dateTime }

            val anchorTime = LocalTime.of(8, 15)
            val anchorPrice = nxtCandles.firstOrNull { it.dateTime.toLocalTime() >= anchorTime }?.closePrice
                ?: run { log.warn("backfill: no 08:15 candle for {}, skipping", date); return@forEach }

            fun gapRate(price: Long) = (price.toDouble() / anchorPrice - 1) * 100
            fun priceAt(candles: List<MinuteCandle>, time: LocalTime) =
                candles.firstOrNull { it.dateTime.toLocalTime() >= time }?.closePrice

            val gap2At1100 = priceAt(regularCandles, LocalTime.of(11, 0))?.let { gapRate(it) }
            val gap2At1400 = priceAt(regularCandles, LocalTime.of(14, 0))?.let { gapRate(it) }
            val gap2At2000 = priceAt(nxtCandles, LocalTime.of(20, 0))?.let { gapRate(it) }

            if (existing != null) {
                // 기존 레코드의 null 체크포인트만 채움
                if (existing.gap2At1100 == null) existing.gap2At1100 = gap2At1100
                if (existing.gap2At1400 == null) existing.gap2At1400 = gap2At1400
                if (existing.gap2At2000 == null) existing.gap2At2000 = gap2At2000
                dailyRepository.save(existing)
                log.info("backfill: patched {} checkpoints", date)
            } else {
                val prevClose = prevTradingDayClose(date, closePriceByDate)
                    ?: run { log.warn("backfill: no prevClose for {}, skipping", date); return@forEach }

                val rawGap1 = (anchorPrice.toDouble() / prevClose - 1) * 100
                val gap1 = if (prevGap2At1400 != null && prevGap2At2000 != null) {
                    val prevAfterMarket = ((1 + prevGap2At2000!! / 100) / (1 + prevGap2At1400!! / 100) - 1) * 100
                    ((1 + rawGap1 / 100) / (1 + prevAfterMarket / 100) - 1) * 100
                } else rawGap1

                val allAfterAnchor = (nxtCandles + regularCandles).filter { it.dateTime.toLocalTime() >= anchorTime }
                val gap2Close = regularCandles.lastOrNull()?.closePrice?.let { gapRate(it) }
                    ?: allAfterAnchor.lastOrNull()?.closePrice?.let { gapRate(it) } ?: 0.0
                val gap2High = allAfterAnchor.maxOfOrNull { gapRate(it.closePrice) } ?: gap2Close
                val gap2Low = allAfterAnchor.minOfOrNull { gapRate(it.closePrice) } ?: gap2Close

                dailyRepository.save(
                    RegimeDailyRecord(
                        date = date,
                        gap1 = gap1,
                        gap2Close = gap2Close,
                        gap2High = gap2High,
                        gap2Low = gap2Low,
                        gap2At1100 = gap2At1100,
                        gap2At1400 = gap2At1400,
                        gap2At2000 = gap2At2000,
                    )
                )
                log.info("backfill: saved {} gap1={} gap2Close={}", date, "%.2f".format(gap1), "%.2f".format(gap2Close))
            }

            prevGap2At1400 = gap2At1400
            prevGap2At2000 = gap2At2000
        }
    }

    private fun recentTradingDays(n: Int): List<LocalDate> {
        val days = mutableListOf<LocalDate>()
        var d = LocalDate.now().minusDays(1)
        while (days.size < n) {
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) days.add(d)
            d = d.minusDays(1)
        }
        return days.reversed()
    }

    private fun prevTradingDayClose(date: LocalDate, closePriceByDate: Map<LocalDate, Long>): Long? =
        closePriceByDate.keys.filter { it < date }.maxOrNull()?.let { closePriceByDate[it] }
}
