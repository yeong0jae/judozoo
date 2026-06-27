package at.backend.leadingstock.application

import at.backend.leadingstock.domain.MinuteCandles
import at.backend.leadingstock.domain.SignalEvent
import at.backend.leadingstock.domain.SignalLabel
import at.backend.leadingstock.infrastructure.repository.SignalEventRepository
import at.backend.leadingstock.infrastructure.repository.SignalLabelRepository
import at.backend.platform.kiwoom.client.KiwoomMarketClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * 시그널 사후 분석 — 신호에 사후 수익률(라벨)을 붙이고, 종목 묶음·종류별 통계로 가공해 전시한다.
 * 라벨링은 멱등(같은 날 다시 눌러도 갱신). 당일 장중에 누르면 미확정 호라이즌은 null로 남는다.
 */
@Service
class SignalAnalysisService(
    private val signalEventRepository: SignalEventRepository,
    private val signalLabelRepository: SignalLabelRepository,
    private val marketClient: KiwoomMarketClient,
) {

    /**
     * [date]의 모든 신호를 라벨링한다. 종목별로 그날 분봉을 1회만 조회(캐시 공유)해 각 신호의 사후 수익률을 계산.
     * 반환값은 라벨링한 신호 수.
     */
    @Transactional
    fun labelDate(date: LocalDate): Int {
        val events = signalEventRepository.findByTradeDateOrderByOccurredAtDesc(date)
        if (events.isEmpty()) return 0

        val existing = signalLabelRepository.findBySignalEventIdIn(events.map { it.id })
            .associateBy { it.signalEventId }

        val labels = events.groupBy { it.stockCode }.flatMap { (stockCode, stockEvents) ->
            // ka10080은 base_dt 기준 며칠치를 함께 주므로 그날 봉만 추려야 고점·종가가 오염되지 않는다.
            val candles = MinuteCandles(
                marketClient.fetchHistoricalMinuteCandles(stockCode, date)
                    .filter { it.dateTime.toLocalDate() == date },
            )
            stockEvents.map { e ->
                val result = candles.labelFor(e.occurredAt, e.currentPrice)
                existing[e.id]?.also { it.apply(result) }
                    ?: SignalLabel.of(e.id, e.currentPrice, result)
            }
        }
        signalLabelRepository.saveAll(labels)
        return labels.size
    }

    @Transactional(readOnly = true)
    fun analysisOf(date: LocalDate): SignalAnalysis {
        val dayEvents = signalEventRepository.findByTradeDateOrderByOccurredAtDesc(date)
        val dayLabels = signalLabelRepository.findBySignalEventIdIn(dayEvents.map { it.id })
            .associateBy { it.signalEventId }

        // 전체 누적 통계 — 표본이 적은 동안만 전건 로드로 충분. 데이터가 커지면 집계 쿼리/캐시로 전환.
        val allEvents = signalEventRepository.findAll()
        val allLabels = signalLabelRepository.findAll().associateBy { it.signalEventId }

        return SignalAnalysis(
            date = date,
            dayStats = aggregate(dayEvents) { dayLabels[it.id] },
            overallStats = aggregate(allEvents) { allLabels[it.id] },
            stocks = groupByStock(dayEvents) { dayLabels[it.id] },
        )
    }

    private fun aggregate(events: List<SignalEvent>, label: (SignalEvent) -> SignalLabel?): List<SignalKindStat> =
        events.groupBy { it.kind() }.map { (kind, group) ->
            SignalKindStat(
                kind = kind,
                count = group.size,
                labeled = group.count { label(it) != null },
                metrics = metricsOf(group, label),
                byBucket = group.groupBy { it.timeBucket() }
                    .map { (bucket, sub) ->
                        TimeBucketStat(
                            bucket = bucket,
                            count = sub.size,
                            labeled = sub.count { label(it) != null },
                            metrics = metricsOf(sub, label),
                        )
                    }
                    .sortedBy { it.bucket.ordinal },
            )
        }.sortedBy { it.kind.ordinal }

    private fun metricsOf(events: List<SignalEvent>, label: (SignalEvent) -> SignalLabel?): SignalMetrics {
        val labels = events.mapNotNull(label)
        val ret20s = labels.mapNotNull { it.ret20m }
        return SignalMetrics(
            avg1m = labels.mapNotNull { it.ret1m }.avg(),
            avg2m = labels.mapNotNull { it.ret2m }.avg(),
            avg20m = ret20s.avg(),
            avg2h = labels.mapNotNull { it.ret2h }.avg(),
            avgClose = labels.mapNotNull { it.retClose }.avg(),
            avgMfe = labels.mapNotNull { it.mfe }.avg(),
            avgMae = labels.mapNotNull { it.mae }.avg(),
            winRate20m = ret20s.takeIf { it.isNotEmpty() }
                ?.let { it.count { r -> r > 0 }.toDouble() / it.size * 100 },
        )
    }

    private fun groupByStock(events: List<SignalEvent>, label: (SignalEvent) -> SignalLabel?): List<StockSignalGroup> =
        events.groupBy { it.stockCode }.map { (code, group) ->
            val sorted = group.sortedBy { it.occurredAt }
            val rows = sorted.map { e ->
                val l = label(e)
                SignalRow(
                    occurredAt = e.occurredAt,
                    kind = e.kind(),
                    currentPrice = e.currentPrice,
                    priceChangeRate = e.priceChangeRate,
                    gapRate = e.gapRate,
                    spikeRatio = e.spikeRatio,
                    ret1m = l?.ret1m,
                    ret2m = l?.ret2m,
                    ret20m = l?.ret20m,
                    ret2h = l?.ret2h,
                    retClose = l?.retClose,
                    mfe = l?.mfe,
                    mae = l?.mae,
                )
            }
            StockSignalGroup(
                stockCode = code,
                stockName = sorted.first().stockName,
                theme = sorted.firstNotNullOfOrNull { it.theme },
                bestMfe = rows.mapNotNull { it.mfe }.maxOrNull(),
                closeRet = rows.first().retClose,
                signals = rows,
            )
        }.sortedByDescending { it.bestMfe ?: Double.NEGATIVE_INFINITY }

    private fun List<Double>.avg(): Double? = takeIf { it.isNotEmpty() }?.average()
}
