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

/**
 * 주도주 캘린더 — 마감 스냅샷과 월 조회.
 *
 * 무엇이 주도주인지는 홈 카드가 정한다. 여기서는 그 답을 마감 때 한 번 받아 남긴다.
 * 마감 기록 전의 오늘은 타임라인이 매분 남긴 마지막 분으로 채워 보인다 — 같은 답을 같은 풀에서 받은 것이다.
 */
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

    /** 캘린더 칸의 종목 한 줄 — 마감 기록이든 타임라인의 마지막 분이든 같은 모양이다. */
    data class LeaderStock(
        val rank: Int,
        val exchange: String?,
        val code: String,
        val name: String,
        val price: BigDecimal,
        val changeRate: Double,
        val tradingValue: BigDecimal,
    )

    /** [live]면 마감 기록 전의 오늘 — 타임라인의 마지막 분이라 순위가 아직 바뀐다. */
    data class RecordedDay(val tradeDate: LocalDate, val stocks: List<LeaderStock>, val closed: Boolean = false, val live: Boolean = false)

    /**
     * 국내 주도주를 [tradeDate]로 남긴다. 풀 조회가 실패하면 예외가 그대로 올라가고 아무것도 남지 않는다 —
     * 0개로 저장하면 브로커 오류가 "주도주 없음"으로 영구히 남는다.
     */
    @Transactional
    fun snapshotDomestic(tradeDate: LocalDate, at: LocalDateTime): Int {
        val leaders = domestic.findLeaders(LEADERS_COUNT)
        return record(Region.KR, tradeDate, at, closed = false) { id -> leaders.mapIndexed { i, s -> LeaderDayStock.domestic(id, i + 1, s) } }
    }

    /** 해외 주도주를 뉴욕 거래일 [tradeDate]로 남긴다. 실패 처리는 국내와 같다. */
    @Transactional
    fun snapshotOverseas(tradeDate: LocalDate, at: LocalDateTime): Int {
        val leaders = overseas.leaders(LEADERS_COUNT)
        return record(Region.US, tradeDate, at, closed = false) { id -> leaders.mapIndexed { i, s -> LeaderDayStock.overseas(id, i + 1, s) } }
    }

    /** 휴장일을 남긴다 — 날 행만 있고 종목은 없다. 캘린더가 "기록 없음"과 휴장을 가를 수 있게. */
    @Transactional
    fun recordClosed(region: Region, tradeDate: LocalDate, at: LocalDateTime) {
        record(region, tradeDate, at, closed = true) { emptyList() }
    }

    /** (국내 날들, 해외 날들). 해외는 **그달 1일 직전 평일부터** — 1일 칸에 붙는 해외장이 전달에 있다. */
    @Transactional(readOnly = true)
    fun findMonth(year: Int, month: Int): Pair<List<RecordedDay>, List<RecordedDay>> {
        val first = LocalDate.of(year, month, 1)
        val after = first.plusMonths(1)
        val usStart = ForeignSession.pairedWith(first)
        return withToday(Region.KR, recorded(Region.KR, first, after), first, after) to
            withToday(Region.US, recorded(Region.US, usStart, after), usStart, after)
    }

    /** 같은 (시장, 거래일)이 있으면 지우고 새로 쓴다 — 재기동·수동 재실행이 한 벌만 남긴다. */
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

    /**
     * 마감 기록이 아직 없는 오늘을 타임라인의 마지막 분으로 채운다. 타임라인은 장 시간·휴장일을 이미 걸러 찍으므로,
     * 장 전에 풀이 들고 있는 어제 값이 오늘 칸에 들어오지 않는다.
     */
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
        /** 홈 주도주 카드의 줄 수와 같다 */
        const val LEADERS_COUNT = 5
    }
}
