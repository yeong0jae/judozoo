package judozoo.market.application

import judozoo.library.KST
import judozoo.market.domain.FuturesInvestorSnapshot
import judozoo.market.domain.FuturesInvestorSnapshotRepository
import judozoo.market.domain.FuturesNets
import judozoo.market.domain.InvestorSession
import judozoo.market.domain.InvestorSessions
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

    fun investorSessions(market: Market, requested: LocalDate): InvestorSessions<Nets> {
        val on = snapshots.latestDateOnOrBefore(market, requested) ?: requested
        fun at(session: InvestorSession) = snapshotAt(market, on.atTime(session.end))
        val open = at(InvestorSession.PRE_MARKET)
        val morning = at(InvestorSession.MORNING)
        val afternoon = at(InvestorSession.AFTERNOON)
        val close = at(InvestorSession.CLOSING)
        val afterClose = at(InvestorSession.AFTER_MARKET)
        fun diff(later: MarketInvestorSnapshot?, earlier: MarketInvestorSnapshot?) = if (later == null || earlier == null) null else later.nets() - earlier.nets()

        val sessions = listOf(
            InvestorSessions.Net(InvestorSession.PRE_MARKET, open?.nets()),
            InvestorSessions.Net(InvestorSession.MORNING, if (open != null) diff(morning, open) else morning?.nets()),
            InvestorSessions.Net(InvestorSession.AFTERNOON, diff(afternoon, morning)),
            InvestorSessions.Net(InvestorSession.CLOSING, diff(close, afternoon)),
            InvestorSessions.Net(InvestorSession.AFTER_MARKET, diff(afterClose, close)),
        )
        if (on != today()) return InvestorSessions(on, sessions)
        val rows = snapshots.findByMarketAndTradeDateOrderByCapturedAtDesc(market, on, Limit.of(2))
        if (rows.size < 2) return InvestorSessions(on, sessions)
        val delta = rows[0].nets() - rows[1].nets()
        return InvestorSessions(on, sessions.map { if (it.nets != null && rows[0].capturedAt in it.session) it.copy(delta = delta) else it })
    }

    fun futuresInvestorDailyHistory(market: Market, count: Int): List<FuturesInvestorDay> =
        futuresSnapshots.tradeDates(market, Limit.of(count + HOLIDAY_MARGIN + 1))
            .filter { calendar.isOpen(it) != false }
            .take(count)
            .map { day -> FuturesInvestorDay(day, futuresSnapshots.findByMarketAndTradeDateOrderByCapturedAtDesc(market, day, Limit.of(1)).single().nets()) }

    fun futuresInvestorSessions(market: Market, requested: LocalDate): InvestorSessions<FuturesNets> {
        val on = futuresSnapshots.latestDateOnOrBefore(market, requested) ?: requested
        fun at(session: InvestorSession) = futuresSnapshotAt(market, on.atTime(session.end))
        val morning = at(InvestorSession.FUTURES_MORNING)
        val afternoon = at(InvestorSession.FUTURES_AFTERNOON)
        val close = at(InvestorSession.FUTURES_CLOSING)
        fun diff(later: FuturesInvestorSnapshot?, earlier: FuturesInvestorSnapshot?) = if (later == null || earlier == null) null else later.nets() - earlier.nets()

        val sessions = listOf(
            InvestorSessions.Net(InvestorSession.FUTURES_MORNING, morning?.nets()),
            InvestorSessions.Net(InvestorSession.FUTURES_AFTERNOON, diff(afternoon, morning)),
            InvestorSessions.Net(InvestorSession.FUTURES_CLOSING, diff(close, afternoon)),
        )
        if (on != today()) return InvestorSessions(on, sessions)
        val rows = futuresSnapshots.findByMarketAndTradeDateOrderByCapturedAtDesc(market, on, Limit.of(2))
        if (rows.size < 2) return InvestorSessions(on, sessions)
        val delta = rows[0].nets() - rows[1].nets()
        return InvestorSessions(on, sessions.map { if (it.nets != null && rows[0].capturedAt in it.session) it.copy(delta = delta) else it })
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
        private const val HOLIDAY_MARGIN = 5

        private val NET_SNAPSHOT_START = LocalTime.of(8, 0)
    }
}
