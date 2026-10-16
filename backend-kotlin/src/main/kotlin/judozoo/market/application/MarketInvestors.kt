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
import judozoo.platform.kiwoom.SectorInvestorNetBuy
import judozoo.stock.domain.Market
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 투자자 수급 — 세션별 / 일별 / 오늘 누적. 현물(억원)과 선물(계약).
 *
 * 세션 순매수는 직접 주는 API가 없어 폴러가 찍어둔 **당일 누적 스냅샷의 경계 diff**로 만든다.
 * 경계 시각이 어긋나면 값이 통째로 틀린 채 그럴듯하게 보인다.
 */
@Service
@Transactional(readOnly = true)
class MarketInvestors(
    private val snapshots: MarketInvestorSnapshotRepository,
    private val futuresSnapshots: FuturesInvestorSnapshotRepository,
    private val calendar: MarketCalendar,
    private val quotes: MarketQuotes,
    private val clock: Clock,
) {
    /** 기관 세부 순매수(억원) — 키움 7종. 일별 표의 표시 순서다. */
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

    /** [delta]는 직전 스냅샷 대비 변화량. 마지막 스냅샷이 속한 구간에만, 그리고 오늘만 실린다. */
    data class SessionNet(val name: String, val time: String, val nets: Nets?, val delta: Nets? = null)

    data class FuturesSessionNet(val name: String, val time: String, val nets: FuturesNets?, val delta: FuturesNets? = null)

    data class FuturesInvestorDay(val date: LocalDate, val nets: FuturesNets)

    /** 투자자 넷 — 현물은 억원, 선물은 계약이다. */
    data class TodayNetInvestors(val individual: Long, val foreign: Long, val institution: Long, val otherCorp: Long)

    /**
     * 지수 한 칸의 당일 누적 순매수. [nets]는 수급만 못 받았을 때 null — 시세는 왔으므로 칸은 남는다.
     * [tradeDate]는 그 수급의 날짜다 — 장 열기 전·휴장일엔 직전 거래일이라 화면이 "10/2 기준"을 붙인다.
     */
    data class TodayNet(val market: Market, val futures: Boolean, val indexValue: Double, val changeRate: Double, val nets: TodayNetInvestors?, val tradeDate: LocalDate?)

    /**
     * 최근 [count] 거래일 일별 순매수. 각 거래일의 **마지막 스냅샷** = 그날 누적. 최신순.
     * 폴러가 적재한 날만 나온다(과거 소급 불가). 휴장일에 잘못 적재된 스냅샷은 제외한다.
     */
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
            // ka10051은 장 초반에 직전 거래일 값을 오늘로 돌려줄 때가 있고, 폴러가 그걸 오늘로 적재한다.
            // 오늘 마지막 값이 직전 거래일과 모든 수급 항목에서 같다면 그 복사본으로 본다
            if (out.isNotEmpty() && out[0].date == today && out[0].copy(date = day) == entry) out.removeAt(0)
            out += entry
            if (out.size > count) break
        }
        return out.take(count)
    }

    /**
     * 세션별 순매수 — 당일 누적 스냅샷 경계 diff. 데이터 없는 세션은 nets=null. (실제로 조회한 날짜, 세션들)
     *
     * 다섯 구간의 합 = 그날 최종 누적. 프리 스냅샷이 없던 과거는 오전에 프리를 포함해 **합을 보존**한다.
     * 공휴일이면 직전 거래일로 물러나므로 **실제로 조회한 날짜를 함께 돌려준다**.
     */
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

    /** 최근 [count] 거래일 일별 선물 순매수(계약). 각 거래일의 마지막 스냅샷. 최신순. 휴장일에 찍힌 스냅샷은 제외한다. */
    fun futuresInvestorDailyHistory(market: Market, count: Int): List<FuturesInvestorDay> =
        futuresSnapshots.tradeDates(market, Limit.of(count + HOLIDAY_MARGIN + 1))
            .filter { calendar.isOpen(it) != false }
            .take(count)
            .map { day -> FuturesInvestorDay(day, futuresSnapshots.findByMarketAndTradeDateOrderByCapturedAtDesc(market, day, Limit.of(1)).single().nets()) }

    /** 선물 세션별 순매수(계약) — 스냅샷이 없는 세션은 nets=null. */
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

    /**
     * 코스피·코스닥 현물과 두 지수선물의 당일 누적 순매수.
     *
     * 현물(억원)과 선물(계약)은 단위가 달라 서로 더하지 않는다. 장 전·휴장일엔 직전 거래일 스냅샷을 쓰고,
     * 시세조차 못 받은 시장만 목록에서 빠진다.
     */
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

    /** 장이 아직 안 열렸거나(08시 전) 쉬는 날이면 직전 거래일의 마지막 스냅샷. 장중에 오늘 것이 없으면(폴러 실패) null — 지난날 값을 오늘 카드에 섞지 않는다. */
    private fun <T> beforeOpen(at: LocalDateTime, latest: () -> T?): T? =
        if (!at.toLocalTime().isBefore(NET_SNAPSHOT_START) && !calendar.isHoliday(Region.KR)) null else latest()

    /** 한 시장의 당일 누적 순매수를 [at] 시각으로 남긴다 — 주도주 시장 시그널 폴러가 1분마다 부른다. */
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

    /** 그날 [at] 이하 가장 가까운 스냅샷 — 시그널이 난 시점의 값. */
    fun investorSnapshotAt(market: Market, at: LocalDateTime): MarketInvestorSnapshot? = snapshotAt(market, at)

    /** [at] 이하 가장 최근 스냅샷. 장 전·휴장일엔 직전 거래일의 마지막 스냅샷이다. */
    fun latestInvestorSnapshot(market: Market, at: LocalDateTime): MarketInvestorSnapshot? =
        snapshots.findFirstByMarketAndCapturedAtLessThanEqualOrderByTradeDateDescCapturedAtDesc(market, at)

    private fun snapshotAt(market: Market, at: LocalDateTime) =
        snapshots.findByMarketAndTradeDateAndCapturedAtLessThanEqualOrderByCapturedAtDesc(market, at.toLocalDate(), at, Limit.of(1)).firstOrNull()

    private fun futuresSnapshotAt(market: Market, at: LocalDateTime) =
        futuresSnapshots.findByMarketAndTradeDateAndCapturedAtLessThanEqualOrderByCapturedAtDesc(market, at.toLocalDate(), at, Limit.of(1)).firstOrNull()

    private fun now() = LocalDateTime.now(clock.withZone(KST))

    private fun today() = LocalDate.now(clock.withZone(KST))

    companion object {
        // 세션 경계 — 통합(KRX+NXT) 기준이라 프리(08:00~09:00)·애프터(15:40~20:00)도 누적에 들어간다
        private val OPEN = LocalTime.of(9, 0)
        private val MORNING_END = LocalTime.of(12, 0)
        private val AFTERNOON_END = LocalTime.of(15, 0)
        private val CLOSE = LocalTime.of(15, 40)
        private val AFTER_END = LocalTime.of(20, 0)

        private val FUT_MORNING_END = LocalTime.of(12, 0)
        private val FUT_AFTERNOON_END = LocalTime.of(15, 0)
        private val FUT_CLOSE = LocalTime.of(15, 45)

        /** 휴장일 스냅샷을 걸러내도 count를 채우도록 더 읽는 여유분 */
        private const val HOLIDAY_MARGIN = 5

        /** 수급 폴러가 그날 첫 스냅샷을 남기는 시각(NXT 프리마켓 개장). 이전엔 오늘 수급이 있을 수 없다. */
        private val NET_SNAPSHOT_START = LocalTime.of(8, 0)

        /** "09:00~12:00" 구간이 [at]을 품는가 — 시작 포함, 끝 미포함. */
        private fun contains(range: String, at: LocalDateTime): Boolean {
            val (start, end) = range.split("~")
            val hm = at.toLocalTime().toString().take(5)
            return start <= hm && hm < end
        }
    }
}
