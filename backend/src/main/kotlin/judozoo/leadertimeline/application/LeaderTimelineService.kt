package judozoo.leadertimeline.application

import judozoo.leadertimeline.domain.LeaderTick
import judozoo.leadertimeline.domain.LeaderTickRepository
import judozoo.leadertimeline.domain.LeaderTickStock
import judozoo.leadertimeline.domain.LeaderTickStockRepository
import judozoo.leadingstock.application.LeadingStockService
import judozoo.market.domain.Region
import judozoo.overseasleadingstock.application.OverseasLeadingStockService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime

@Service
class LeaderTimelineService(
    private val ticks: LeaderTickRepository,
    private val stocks: LeaderTickStockRepository,
    private val domestic: LeadingStockService,
    private val overseas: OverseasLeadingStockService,
) {
    data class RecordedTick(val at: LocalDateTime, val takenAt: LocalDateTime, val stocks: List<LeaderTickStock>)

    @Transactional
    fun snapshot(region: Region, at: LocalDateTime, takenAt: LocalDateTime): Int {
        val domesticLeaders = if (region == Region.KR) domestic.findLeaders(LEADERS_COUNT) else emptyList()
        val overseasLeaders = if (region == Region.US) overseas.leaders(LEADERS_COUNT) else emptyList()
        val old = ticks.findByRegionAndAt(region, at).mapNotNull { it.id }
        if (old.isNotEmpty()) {
            stocks.deleteByTickIds(old)
            ticks.deleteAllByIdInBatch(old)
        }
        val tickId = ticks.saveAndFlush(LeaderTick(region, at.toLocalDate(), at, takenAt)).id!!
        val rows = domesticLeaders.mapIndexed { i, s -> LeaderTickStock.domestic(tickId, i + 1, s) } +
            overseasLeaders.mapIndexed { i, s -> LeaderTickStock.overseas(tickId, i + 1, s) }
        stocks.saveAll(rows)
        return rows.size
    }

    @Transactional(readOnly = true)
    fun findDay(region: Region, tradeDate: LocalDate, since: LocalDateTime? = null): List<RecordedTick> {
        val found = if (since == null) ticks.findByRegionAndTradeDateOrderByAt(region, tradeDate)
        else ticks.findByRegionAndTradeDateAndAtAfterOrderByAt(region, tradeDate, since)
        return withStocks(found)
    }

    @Transactional(readOnly = true)
    fun latest(region: Region, tradeDate: LocalDate): RecordedTick? =
        ticks.findFirstByRegionAndTradeDateOrderByAtDesc(region, tradeDate)?.let { withStocks(listOf(it)).single() }

    private fun withStocks(found: List<LeaderTick>): List<RecordedTick> {
        if (found.isEmpty()) return emptyList()
        val byTick = stocks.findByLeaderTickIdInOrderByRank(found.mapNotNull { it.id }).groupBy { it.leaderTickId }
        return found.map { RecordedTick(it.at, it.createdAt, byTick[it.id].orEmpty()) }
    }

    companion object {
        const val LEADERS_COUNT = 5
    }
}
