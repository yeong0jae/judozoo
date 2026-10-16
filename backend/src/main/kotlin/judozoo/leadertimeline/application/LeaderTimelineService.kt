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

/**
 * 주도주 타임라인 — 1분 스냅샷과 하루 조회.
 *
 * 무엇이 주도주인지는 홈 카드가 정한다. 여기서는 그 답을 1분마다 받아 남긴다.
 * 풀은 장중 15초마다 갈아 끼워지는 캐시라 브로커를 새로 부르지 않는다.
 */
@Service
class LeaderTimelineService(
    private val ticks: LeaderTickRepository,
    private val stocks: LeaderTickStockRepository,
    private val domestic: LeadingStockService,
    private val overseas: OverseasLeadingStockService,
) {
    data class RecordedTick(val at: LocalDateTime, val takenAt: LocalDateTime, val stocks: List<LeaderTickStock>)

    /**
     * 그 분의 주도주를 남긴다. 같은 (시장, 분)이 있으면 지우고 새로 쓴다 — 재기동해도 한 벌만 남는다.
     *
     * 풀 조회가 실패하면 예외가 그대로 올라가고 아무것도 남지 않는다 — 0개로 남기면 브로커 오류가 "주도주 없음"으로 굳는다.
     */
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

    /** 그날의 분들, 시각 순. [since]가 있으면 그 **뒤** 분만 — 장중에 새 분만 받아 붙일 때 쓴다. */
    @Transactional(readOnly = true)
    fun findDay(region: Region, tradeDate: LocalDate, since: LocalDateTime? = null): List<RecordedTick> {
        val found = if (since == null) ticks.findByRegionAndTradeDateOrderByAt(region, tradeDate)
        else ticks.findByRegionAndTradeDateAndAtAfterOrderByAt(region, tradeDate, since)
        return withStocks(found)
    }

    /** 그날 마지막 분. 캘린더가 마감 기록 전의 오늘 칸을 채울 때 쓴다. */
    @Transactional(readOnly = true)
    fun latest(region: Region, tradeDate: LocalDate): RecordedTick? =
        ticks.findFirstByRegionAndTradeDateOrderByAtDesc(region, tradeDate)?.let { withStocks(listOf(it)).single() }

    private fun withStocks(found: List<LeaderTick>): List<RecordedTick> {
        if (found.isEmpty()) return emptyList()
        val byTick = stocks.findByLeaderTickIdInOrderByRank(found.mapNotNull { it.id }).groupBy { it.leaderTickId }
        return found.map { RecordedTick(it.at, it.createdAt, byTick[it.id].orEmpty()) }
    }

    companion object {
        /** 홈 주도주 카드의 줄 수와 같다 */
        const val LEADERS_COUNT = 5
    }
}
