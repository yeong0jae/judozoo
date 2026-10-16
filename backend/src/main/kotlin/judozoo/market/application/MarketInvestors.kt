package judozoo.market.application

import judozoo.library.KST
import judozoo.market.domain.FuturesInvestorSnapshot
import judozoo.market.domain.FuturesInvestorSnapshotRepository
import judozoo.market.domain.FuturesNets
import judozoo.market.domain.MarketInvestorSnapshot
import judozoo.market.domain.MarketInvestorSnapshotRepository
import judozoo.market.domain.Nets
import judozoo.market.domain.Region
import judozoo.market.domain.SessionOrg
import judozoo.platform.kis.KisFutures
import judozoo.platform.kiwoom.SectorInvestorNetBuy
import judozoo.stock.domain.Market
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@Service
@Transactional(readOnly = true)
class MarketInvestors(
    private val snapshots: MarketInvestorSnapshotRepository,
    private val futuresSnapshots: FuturesInvestorSnapshotRepository,
    private val calendar: MarketCalendar,
    private val quotes: MarketQuotes,
    private val clock: Clock,
) {
    data class OrgBreakdown(
        val financialInvestmentEok: Long,
        val trustEok: Long,
        val pensionFundEok: Long,
        val privateEquityEok: Long,
        val insuranceEok: Long,
        val bankEok: Long,
        val otherFinanceEok: Long,
    )

    data class InvestorDay(val date: LocalDate, val individualEok: Long, val foreignEok: Long, val institutionEok: Long, val otherCorpEok: Long, val breakdown: OrgBreakdown)

    data class SessionNet(val name: String, val time: String, val nets: Nets?, val delta: Nets? = null)

    data class FuturesSessionNet(val name: String, val time: String, val nets: FuturesNets?, val delta: FuturesNets? = null)

    data class FuturesInvestorDay(val date: LocalDate, val nets: FuturesNets)

    data class TodayNetInvestors(val individual: Long, val foreign: Long, val institution: Long, val otherCorp: Long)

    data class TodayNet(val market: Market, val futures: Boolean, val indexValue: Double, val changeRate: Double, val nets: TodayNetInvestors?, val tradeDate: LocalDate?)

    fun investorDailyHistory(market: Market, count: Int): List<InvestorDay> {
        val today = today()
        val out = mutableListOf<InvestorDay>()
        for (day in snapshots.tradeDates(market, Limit.of(count + HOLIDAY_MARGIN + 1))) {
            if (calendar.isOpen(day) == false) continue
            val row = snapshots.findByMarketAndTradeDateOrderByCapturedAtDesc(market, day, Limit.of(1)).single()
            val entry = InvestorDay(
                day, row.individualEok, row.foreignEok, row.institutionEok, row.otherCorpEok,
                OrgBreakdown(row.financialInvestmentEok, row.trustEok, row.pensionFundEok, row.privateEquityEok, row.insuranceEok, row.bankEok, row.otherFinanceEok),
            )
            if (out.isNotEmpty() && out[0].date == today && out[0].copy(date = day) == entry) out.removeAt(0)
            out += entry
            if (out.size > count) break
        }
        return out.take(count)
    }

    fun investorSessions(market: Market, requested: LocalDate): Pair<LocalDate, List<SessionNet>> {
        val on = snapshots.latestDateOnOrBefore(market, requested) ?: requested
        fun at(t: LocalTime) = snapshotAt(market, on.atTime(t))
        val (open, morning, afternoon, close, afterClose) = listOf(OPEN, MORNING_END, AFTERNOON_END, CLOSE, AFTER_END).map(::at)
        fun diff(later: MarketInvestorSnapshot?, earlier: MarketInvestorSnapshot?) = if (later == null || earlier == null) null else later.nets() - earlier.nets()

        val sessions = listOf(
            SessionNet("프리마켓", "08:00~09:00", open?.nets()),
            SessionNet("오전", "09:00~12:00", if (open != null) diff(morning, open) else morning?.nets()),
            SessionNet("오후", "12:00~15:00", diff(afternoon, morning)),
            SessionNet("마감 구간", "15:00~15:40", diff(close, afternoon)),
            SessionNet("애프터마켓", "15:40~20:00", diff(afterClose, close)),
        )
        if (on != today()) return on to sessions
        val (latest, previous) = snapshots.findByMarketAndTradeDateOrderByCapturedAtDesc(market, on, Limit.of(2)).let { it.getOrNull(0) to it.getOrNull(1) }
        if (latest == null || previous == null) return on to sessions
        val delta = latest.nets() - previous.nets()
        return on to sessions.map { if (it.nets != null && contains(it.time, latest.capturedAt)) it.copy(delta = delta) else it }
    }

    fun futuresInvestorDailyHistory(market: Market, count: Int): List<FuturesInvestorDay> =
        futuresSnapshots.tradeDates(market, Limit.of(count + HOLIDAY_MARGIN + 1))
            .filter { calendar.isOpen(it) != false }
            .take(count)
            .map { day -> FuturesInvestorDay(day, futuresSnapshots.findByMarketAndTradeDateOrderByCapturedAtDesc(market, day, Limit.of(1)).single().nets()) }

    fun futuresInvestorSessions(market: Market, requested: LocalDate): Pair<LocalDate, List<FuturesSessionNet>> {
        val on = futuresSnapshots.latestDateOnOrBefore(market, requested) ?: requested
        fun at(t: LocalTime) = futuresSnapshotAt(market, on.atTime(t))
        val morning = at(FUT_MORNING_END)
        val afternoon = at(FUT_AFTERNOON_END)
        val close = at(FUT_CLOSE)
        fun diff(later: FuturesInvestorSnapshot?, earlier: FuturesInvestorSnapshot?) = if (later == null || earlier == null) null else later.nets() - earlier.nets()

        val sessions = listOf(
            FuturesSessionNet("오전", "08:45~12:00", morning?.nets()),
            FuturesSessionNet("오후", "12:00~15:00", diff(afternoon, morning)),
            FuturesSessionNet("마감 구간", "15:00~15:45", diff(close, afternoon)),
        )
        if (on != today()) return on to sessions
        val rows = futuresSnapshots.findByMarketAndTradeDateOrderByCapturedAtDesc(market, on, Limit.of(2))
        if (rows.size < 2) return on to sessions
        val delta = rows[0].nets() - rows[1].nets()
        return on to sessions.map { if (it.nets != null && contains(it.time, rows[0].capturedAt)) it.copy(delta = delta) else it }
    }

    fun todayNets(): List<TodayNet> {
        val at = now()
        val out = mutableListOf<TodayNet>()
        for (market in Market.entries) {
            val snapshot = snapshotAt(market, at) ?: beforeOpen(at) { snapshots.latestDateOnOrBefore(market, at.toLocalDate())?.let { snapshots.findByMarketAndTradeDateOrderByCapturedAtDesc(market, it, Limit.of(1)).firstOrNull() } }
            if (snapshot != null) {
                out += TodayNet(market, false, snapshot.indexValue, snapshot.changeRate, TodayNetInvestors(snapshot.individualEok, snapshot.foreignEok, snapshot.institutionEok, snapshot.otherCorpEok), snapshot.tradeDate)
            } else {
                val quote = try { quotes.index(market) } catch (e: Exception) { continue }
                out += TodayNet(market, false, quote.currentValue, quote.changeRate, null, null)
            }
        }
        for (market in Market.entries) {
            val quote = quotes.futuresQuote(market) ?: continue
            val snapshot = futuresSnapshotAt(market, at) ?: beforeOpen(at) { futuresSnapshots.latestDateOnOrBefore(market, at.toLocalDate())?.let { futuresSnapshots.findByMarketAndTradeDateOrderByCapturedAtDesc(market, it, Limit.of(1)).firstOrNull() } }
            out += TodayNet(
                market, true, quote.futuresPrice, quote.changeRate,
                snapshot?.let { TodayNetInvestors(it.individualQty, it.foreignQty, it.institutionQty, it.otherCorpQty) }, snapshot?.tradeDate,
            )
        }
        return out
    }

    private fun <T> beforeOpen(at: LocalDateTime, latest: () -> T?): T? =
        if (!at.toLocalTime().isBefore(NET_SNAPSHOT_START) && !calendar.isHoliday(Region.KR)) null else latest()

    @Transactional
    fun record(market: Market, tradeDate: LocalDate, at: LocalDateTime, net: SectorInvestorNetBuy) {
        snapshots.save(
            MarketInvestorSnapshot(
                market, tradeDate, at, net.foreignEok, net.institutionEok, net.individualEok, net.otherCorpEok,
                net.financialInvestmentEok, net.trustEok, net.pensionFundEok, net.privateEquityEok, net.insuranceEok, net.bankEok,
                net.otherFinanceEok, net.indexValue.toFloat(), net.changeRate.toFloat(), at, at,
            ),
        )
    }

    @Transactional
    fun recordFutures(market: Market, tradeDate: LocalDate, at: LocalDateTime, net: KisFutures.FuturesInvestors) {
        val now = now()
        futuresSnapshots.save(
            FuturesInvestorSnapshot(
                market, tradeDate, at, net.foreign, net.institution, net.individual, net.securities, net.insurance, net.merchantBank,
                net.trust, net.privateEquity, net.fund, net.bank, net.otherOrg, net.otherCorp, now, now,
            ),
        )
    }

    fun investorSnapshotAt(market: Market, at: LocalDateTime): MarketInvestorSnapshot? = snapshotAt(market, at)

    fun latestInvestorSnapshot(market: Market, at: LocalDateTime): MarketInvestorSnapshot? =
        snapshots.findFirstByMarketAndCapturedAtLessThanEqualOrderByTradeDateDescCapturedAtDesc(market, at)

    private fun snapshotAt(market: Market, at: LocalDateTime) =
        snapshots.findByMarketAndTradeDateAndCapturedAtLessThanEqualOrderByCapturedAtDesc(market, at.toLocalDate(), at, Limit.of(1)).firstOrNull()

    private fun futuresSnapshotAt(market: Market, at: LocalDateTime) =
        futuresSnapshots.findByMarketAndTradeDateAndCapturedAtLessThanEqualOrderByCapturedAtDesc(market, at.toLocalDate(), at, Limit.of(1)).firstOrNull()

    private fun now() = LocalDateTime.now(clock.withZone(KST))

    private fun today() = LocalDate.now(clock.withZone(KST))

    companion object {
        private val OPEN = LocalTime.of(9, 0)
        private val MORNING_END = LocalTime.of(12, 0)
        private val AFTERNOON_END = LocalTime.of(15, 0)
        private val CLOSE = LocalTime.of(15, 40)
        private val AFTER_END = LocalTime.of(20, 0)

        private val FUT_MORNING_END = LocalTime.of(12, 0)
        private val FUT_AFTERNOON_END = LocalTime.of(15, 0)
        private val FUT_CLOSE = LocalTime.of(15, 45)

        private const val HOLIDAY_MARGIN = 5

        private val NET_SNAPSHOT_START = LocalTime.of(8, 0)

        private fun contains(range: String, at: LocalDateTime): Boolean {
            val (start, end) = range.split("~")
            val hm = at.toLocalTime().toString().take(5)
            return start <= hm && hm < end
        }
    }
}
