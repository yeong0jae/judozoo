package judozoo.leadercalendar.application

import judozoo.leadercalendar.domain.ForeignSession
import judozoo.leadercalendar.domain.LeaderDay
import judozoo.leadercalendar.domain.LeaderDayRepository
import judozoo.leadercalendar.domain.LeaderDayStock
import judozoo.leadercalendar.domain.LeaderDayStockRepository
import judozoo.leadertimeline.application.LeaderTimelineService
import judozoo.leadingstock.application.LeadingStockService
import judozoo.market.domain.Region
import judozoo.overseasleadingstock.application.OverseasLeadingStockService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

@Service
class LeaderCalendarService(
    private val days: LeaderDayRepository,
    private val stocks: LeaderDayStockRepository,
    private val timeline: LeaderTimelineService,
    private val domestic: LeadingStockService,
    private val overseas: OverseasLeadingStockService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class LeaderStock(
        val rank: Int,
        val exchange: String?,
        val code: String,
        val name: String,
        val price: BigDecimal,
        val changeRate: Double,
        val tradingValue: BigDecimal,
    )

    data class RecordedDay(val tradeDate: LocalDate, val stocks: List<LeaderStock>, val closed: Boolean = false, val live: Boolean = false)

    @Transactional
    fun snapshotDomestic(tradeDate: LocalDate, at: LocalDateTime): Int {
        val leaders = domestic.findLeaders(LEADERS_COUNT)
        return record(Region.KR, tradeDate, at, closed = false) { id -> leaders.mapIndexed { i, s -> LeaderDayStock.domestic(id, i + 1, s) } }
    }

    @Transactional
    fun snapshotOverseas(tradeDate: LocalDate, at: LocalDateTime): Int {
        val leaders = overseas.leaders(LEADERS_COUNT)
        return record(Region.US, tradeDate, at, closed = false) { id -> leaders.mapIndexed { i, s -> LeaderDayStock.overseas(id, i + 1, s) } }
    }

    @Transactional
    fun recordClosed(region: Region, tradeDate: LocalDate, at: LocalDateTime) {
        record(region, tradeDate, at, closed = true) { emptyList() }
    }

    @Transactional(readOnly = true)
    fun findMonth(year: Int, month: Int): Pair<List<RecordedDay>, List<RecordedDay>> {
        val first = LocalDate.of(year, month, 1)
        val after = first.plusMonths(1)
        val usStart = ForeignSession.pairedWith(first)
        return withToday(Region.KR, recorded(Region.KR, first, after), first, after) to
            withToday(Region.US, recorded(Region.US, usStart, after), usStart, after)
    }

    private fun record(region: Region, tradeDate: LocalDate, at: LocalDateTime, closed: Boolean, rows: (Long) -> List<LeaderDayStock>): Int {
        val old = days.findByRegionAndTradeDate(region, tradeDate).mapNotNull { it.id }
        if (old.isNotEmpty()) {
            stocks.deleteByDayIds(old)
            days.deleteAllByIdInBatch(old)
        }
        val saved = stocks.saveAll(rows(days.saveAndFlush(LeaderDay(region, tradeDate, at, closed)).id!!))
        log.info("주도주 캘린더 {} {} — {}", region, tradeDate, if (closed) "휴장" else "${saved.size}종목")
        return saved.size
    }

    private fun withToday(region: Region, recorded: List<RecordedDay>, start: LocalDate, end: LocalDate): List<RecordedDay> {
        val today = region.today(clock.instant())
        if (today < start || !today.isBefore(end) || recorded.any { it.tradeDate == today }) return recorded
        val tick = timeline.latest(region, today) ?: return recorded
        return recorded + RecordedDay(today, tick.stocks.map { LeaderStock(it.rank, it.exchange, it.code, it.name, it.price, it.changeRate, it.tradingValue) }, live = true)
    }

    private fun recorded(region: Region, from: LocalDate, until: LocalDate): List<RecordedDay> {
        val found = days.findByRegionAndTradeDateGreaterThanEqualAndTradeDateLessThanOrderByTradeDate(region, from, until)
        if (found.isEmpty()) return emptyList()
        val byDay = stocks.findByLeaderDayIdInOrderByRank(found.mapNotNull { it.id }).groupBy { it.leaderDayId }
        return found.map { day ->
            RecordedDay(
                day.tradeDate,
                byDay[day.id].orEmpty().map { LeaderStock(it.rank, it.exchange, it.code, it.name, it.price, it.changeRate, it.tradingValue) },
                day.closed,
            )
        }
    }

    companion object {
        const val LEADERS_COUNT = 5
    }
}
