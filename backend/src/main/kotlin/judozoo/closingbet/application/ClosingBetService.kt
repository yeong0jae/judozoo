package judozoo.closingbet.application

import judozoo.closingbet.domain.BetPlayer
import judozoo.closingbet.domain.BetPlayerRepository
import judozoo.closingbet.domain.BetRejected
import judozoo.closingbet.domain.BetRound
import judozoo.closingbet.domain.BetRoundRepository
import judozoo.closingbet.domain.BetRoundStock
import judozoo.closingbet.domain.BetRoundStockRepository
import judozoo.closingbet.domain.BetRules
import judozoo.closingbet.domain.Checks
import judozoo.closingbet.domain.ClosingBet
import judozoo.closingbet.domain.ClosingBetEvent
import judozoo.closingbet.domain.ClosingBetEventRepository
import judozoo.closingbet.domain.ClosingBetRepository
import judozoo.closingbet.domain.NicknameRejected
import judozoo.closingbet.domain.Nicknames
import judozoo.closingbet.domain.OpenDays
import judozoo.closingbet.domain.Phase
import judozoo.closingbet.domain.Results
import judozoo.closingbet.domain.SellWindow
import judozoo.closingbet.domain.Stakes
import judozoo.leadingstock.application.LeadingStockService
import judozoo.leadingstock.domain.LeadingStockCriteria
import judozoo.leadingstock.domain.LeadingStockSnapshot
import judozoo.library.KST
import judozoo.library.TtlCache
import judozoo.market.application.MarketCalendar
import judozoo.market.application.MarketInvestors
import judozoo.market.application.MarketQuotes
import judozoo.platform.kiwoom.KiwoomMarket
import judozoo.stock.application.StockCatalog
import judozoo.stock.application.StockInvestorService
import judozoo.stock.domain.Market
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 모의 종가베팅 — 베팅을 받고, 20:00에 체결하고, 아침에 판다.
 *
 * 판정은 전부 `domain`이 한다. 여기서는 시세·수급을 모으고, 행을 읽고 쓴다.
 * 주도주 풀은 `leadingstock`, 시장·선물 수급은 `market`, 종목 수급은 `stock`의 application에서 받는다.
 */
@Service
class ClosingBetService(
    private val bets: ClosingBetRepository,
    private val events: ClosingBetEventRepository,
    private val players: BetPlayerRepository,
    private val newPlayers: BetPlayers,
    private val rounds: BetRoundRepository,
    private val roundStocks: BetRoundStockRepository,
    private val leading: LeadingStockService,
    private val kiwoom: KiwoomMarket,
    private val catalog: StockCatalog,
    private val stockInvestors: StockInvestorService,
    private val marketInvestors: MarketInvestors,
    private val quotes: MarketQuotes,
    private val calendar: MarketCalendar,
    transactions: PlatformTransactionManager,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 트랜잭션은 직접 연다. 처음 베팅하는 사람의 닉네임([BetPlayers])은 자기 트랜잭션에서 먼저 커밋돼야 한다 —
     * 바깥 트랜잭션이 먼저 열려 있으면 그 스냅샷에 새 행이 안 보인다. 체결·정산도 [tick] 안에서 서로를 부른다.
     */
    private val tx = TransactionTemplate(transactions)
    private val json = JsonMapper.builder().build()

    data class PoolStock(val code: String, val name: String, val lead: Boolean, val price: Long, val changeRate: Double)

    /** 종목 수급(백만원). 당일과 최근 5일 합 — 통합(KRX+NXT). */
    data class StockFlows(val foreign: Long, val institution: Long, val foreign5d: Long, val institution5d: Long)

    data class StockReading(
        val stock: PoolStock,
        val market: Market,
        val close: Long,
        val high: Long,
        val low: Long,
        val flows: StockFlows,
        /** 최근 고점과의 거리(%) — 직전 60거래일 최고가 대비 */
        val recentHighGap: Double?,
        /** 15:30 정규장 종가. 정규장이 끝나기 전이면 null */
        val regularClose: Long?,
        val checks: Checks,
    )

    /** 오늘 목록의 등급 묶음 — 종목마다 일봉·수급을 한 번씩 부르니 30초 묶어 둔다(목록 폴링이 여럿이어도 한 번). */
    data class Readings(val flows: Map<String, Any?>, val readings: Map<String, StockReading?>, val nxt: Map<String, Boolean?>)

    data class FeedItem(val id: Long, val nickname: String, val stockName: String, val amountMan: Int, val at: LocalDateTime)

    private val readingsCache = TtlCache<LocalDate, Readings>("closingbetReadings", Duration.ofSeconds(30), maxSize = 2)

    /** 정규장 종가는 15:30에 한 번 정해지면 안 바뀐다 — 잡히면 하루 들고 있고, 그 전에는 분봉을 부르지 않는다. */
    private val regularCloses = TtlCache<Pair<String, LocalDate>, Long?>("closingbetRegularClose", Duration.ofHours(24), maxSize = 200, skipIf = { it == null })

    private fun now(): LocalDateTime = LocalDateTime.now(clock.withZone(KST))

    /** 개장일. 목록을 못 받았으면 앞뒤 30일 평일로 — 휴장 화면이 멀쩡한 평일을 덮지 않게. */
    fun openDays(): OpenDays {
        calendar.openDays().takeIf { it.isNotEmpty() }?.let { return OpenDays(it) }
        val today = now().toLocalDate()
        return OpenDays((-30L..30L).map { today.plusDays(it) }.filter { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY })
    }

    fun phase(at: LocalDateTime = now()): Phase = Phase.at(at, openDays())

    /** 주도주 5 + 후보(오늘 0% 이상 오른 나머지, 거래대금 순). 거래대금 35위·ETF·스팩 거르기는 주도주 목록과 같다. */
    fun pool(): List<PoolStock> {
        val leaders = leading.findLeaders(LEADERS_COUNT)
        val lead = leaders.map { code(it.stockCode) }.toSet()
        return leaders.map { poolStock(it, true) } +
            leading.findCandidates(CANDIDATE_MIN_CHANGE_RATE).filterNot { code(it.stockCode) in lead }.map { poolStock(it, false) }
    }

    /** 한 종목의 종베 체크. 일봉 한 번(오늘 + 60봉)과 수급 한 번. 못 받으면 null — 그 종목은 등급 없이 둔다. */
    fun readStock(stock: PoolStock, on: LocalDate, marketLate: Map<Market, Long>): StockReading? {
        val market = catalog.marketOf(stock.code)
        val candles = kiwoom.fetchDailyCandles(stock.code, DAILY_COUNT, on)
        val todayBar = candles.firstOrNull { it.date == on }
        if (market == null || todayBar == null) {
            log.warn("종베 체크 재료 없음 {} market={} 오늘봉={}", stock.code, market, todayBar != null)
            return null
        }
        val flows = stockFlows(stock.code, on)
        val gap = Checks.recentHighGap(candles.filter { it.date < on }, todayBar.closePrice)
        val regular = regularClose(stock.code, on)
        val checks = Checks.of(
            todayBar.closePrice, todayBar.highPrice, flows.foreign, flows.institution, marketLate[market] ?: 0,
            gap, LeadingStockCriteria().maxHighPositionDropRate, regular,
        )
        return StockReading(stock, market, todayBar.closePrice, todayBar.highPrice, todayBar.lowPrice, flows, gap, regular, checks)
    }

    /** 오늘 목록의 등급·NXT 상장·시장 수급 표 — 30초 묶어 둔다. */
    fun readings(day: LocalDate): Readings = readingsCache.get(day) {
        val flows = marketFlows(day)
        val late = marketLate(flows)
        val stocks = pool()
        Readings(flows, stocks.associate { it.code to readStock(it, day, late) }, stocks.associate { it.code to kiwoom.fetchNxtListed(it.code) })
    }

    /** 시장 수급 표 — 코스피·코스닥 × 외인·기관 × [현물 당일, 현물 5일, 선물 당일, 선물 5일, 마감, 애프터](억원·계약) + 야간선물. */
    fun marketFlows(on: LocalDate): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        for (market in listOf(Market.KOSPI, Market.KOSDAQ)) {
            val spot = marketInvestors.investorDailyHistory(market, FLOW_DAYS)
            val spotToday = spot.firstOrNull { it.date == on }
            val futures = marketInvestors.futuresInvestorDailyHistory(market, FLOW_DAYS)
            val futuresToday = futures.firstOrNull { it.date == on }
            val sessions = marketInvestors.investorSessions(market, on).second
            val close = sessions.firstOrNull { it.name == "마감 구간" }?.nets
            val after = sessions.firstOrNull { it.name == "애프터마켓" }?.nets
            out[market.name] = mapOf(
                "foreign" to listOf(
                    spotToday?.foreignEok ?: 0, spot.sumOf { it.foreignEok }, futuresToday?.nets?.foreign ?: 0, futures.sumOf { it.nets.foreign },
                    close?.foreign ?: 0, after?.foreign ?: 0,
                ),
                "institution" to listOf(
                    spotToday?.institutionEok ?: 0, spot.sumOf { it.institutionEok }, futuresToday?.nets?.institution ?: 0, futures.sumOf { it.nets.institution },
                    close?.institution ?: 0, after?.institution ?: 0,
                ),
            )
        }
        out["night"] = quotes.nightFuturesQuote()?.let { mapOf("price" to it.price, "rate" to it.changeRate) }
        return out
    }

    fun findPlayer(userId: Long): BetPlayer? = players.findById(userId).orElse(null)

    fun rename(userId: Long, raw: String, at: LocalDateTime): BetPlayer {
        newPlayers.ensure(userId, at)
        return inTx { renameEnsured(userId, raw, at) }
    }

    private fun renameEnsured(userId: Long, raw: String, at: LocalDateTime): BetPlayer {
        val me = players.findById(userId).get()
        Nicknames.checkRename(me.renamedAt, at)
        val nick = Nicknames.check(raw, BANNED)
        if (nick == me.nickname) return me
        if (players.existsByNickname(nick)) throw NicknameRejected("이미 있는 닉네임이에요")
        me.rename(nick, at)
        try {
            players.saveAndFlush(me)
        } catch (e: DataIntegrityViolationException) {
            throw NicknameRejected("이미 있는 닉네임이에요")
        }
        return me
    }

    fun myBet(userId: Long, day: LocalDate): ClosingBet? = bets.findByUserIdAndTradingDay(userId, day)

    /** 베팅·변경. 목록과 가격은 **그 순간** 풀로 검증한다 — 뒤에 목록에서 빠져도 베팅은 그대로. */
    fun place(userId: Long, code: String, amountMan: Int, at: LocalDateTime): ClosingBet {
        val phase = phase(at)
        val stocks = pool().associateBy { it.code }
        val stock = stocks[code]
        BetRules.check(phase, amountMan, code, stocks.keys, stock?.price ?: 0)
        val day = phase.betting ?: throw BetRejected("베팅은 15:00부터 20:00까지예요")
        newPlayers.ensure(userId, at)
        return inTx { choose(userId, day, stock!!, amountMan, at) }
    }

    private fun choose(userId: Long, day: LocalDate, stock: PoolStock, amountMan: Int, at: LocalDateTime): ClosingBet {
        val code = stock.code
        val bet = myBet(userId, day)
        if (bet != null && bet.stockCode == code && bet.amountMan == amountMan) return bet
        if (bet != null && bet.stockCode != code) events.save(ClosingBetEvent(day, userId, bet.stockName, -bet.amountMan, at))
        val saved = bets.save((bet ?: ClosingBet(userId, day, code, stock.name, amountMan, at)).apply { choose(code, stock.name, amountMan, at) })
        events.save(ClosingBetEvent(day, userId, stock.name, amountMan, at))
        return saved
    }

    fun cancel(userId: Long, at: LocalDateTime) {
        val phase = phase(at)
        val day = phase.betting?.takeIf { phase.acceptsBets } ?: throw BetRejected("베팅은 15:00부터 20:00까지 바꾸거나 취소할 수 있어요")
        inTx {
            myBet(userId, day)?.let { bet ->
                events.save(ClosingBetEvent(day, userId, bet.stockName, -bet.amountMan, at))
                bets.delete(bet)
            }
        }
    }

    fun stakes(day: LocalDate): Stakes = Stakes(bets.findByTradingDay(day).filter { it.status != ClosingBet.VOID }.map { it.stake() })

    /** LIVE — 최신이 앞. */
    fun feed(day: LocalDate, afterId: Long = 0, limit: Int = 20): List<FeedItem> {
        val rows = events.findByTradingDayAndIdGreaterThanOrderByIdDesc(day, afterId, Limit.of(limit))
        val nicks = nicknames(rows.map { it.userId }.toSet())
        // 닉네임이 없는 사람의 이벤트는 싣지 않는다 — Python의 내부 조인과 같다
        return rows.mapNotNull { e -> nicks[e.userId]?.let { FeedItem(e.id!!, it, e.stockName, e.amountMan, e.at) } }
    }

    /**
     * 개장일 08~20시 매분. 20:00이 지나면 오늘 판을 체결하고, 아침부터는 직전 판을 판다.
     * 둘 다 끝났으면 행 하나 읽고 바로 돌아온다.
     */
    fun tick(at: LocalDateTime) {
        val days = openDays()
        val today = at.toLocalDate()
        if (!days.isOpen(today)) return
        val previous = days.previous(today)
        if (previous != null && !at.toLocalTime().isBefore(Phase.RESULT_START)) settleRound(previous, today, at)
        if (!at.toLocalTime().isBefore(Phase.BET_CLOSE)) fillRound(today, at)
    }

    /**
     * 판을 찍고 걸린 베팅에 매수가·주식 수를 넣는다. 이미 찍었으면 아무것도 안 한다.
     * 매수가는 **일봉 종가**(= 20:00 마지막 체결, 027 0단계). 일봉이 아직 오늘 값이 아니면 false — 몇 분 뒤 다시.
     */
    fun fillRound(day: LocalDate, at: LocalDateTime): Boolean = inTx { fill(day, at) }

    private fun fill(day: LocalDate, at: LocalDateTime): Boolean {
        if (rounds.existsById(day)) return true
        val flows = marketFlows(day)
        val late = marketLate(flows)
        val current = stakes(day)
        val (pots, crowd) = current.byStock() to current.crowd()
        val readings = pool().mapNotNull { readStock(it, day, late) }.toMutableList()
        val dayBets = bets.findByTradingDay(day)
        val betCodes = dayBets.map { it.stockCode }.toSet()
        // 목록에서 빠진 종목에 걸린 베팅 — 그 종목도 판에 넣는다(복기·정산에 필요)
        (betCodes - readings.map { it.stock.code }.toSet()).forEach { code ->
            val name = dayBets.first { it.stockCode == code }.stockName
            readStock(PoolStock(code, name, false, 0, 0.0), day, late)?.let { readings += it }
        }
        val byCode = readings.associateBy { it.stock.code }
        if (!byCode.keys.containsAll(betCodes)) {
            log.warn("종가베팅 체결 보류 — 종가 없는 종목 {}", betCodes - byCode.keys)
            return false
        }
        roundStocks.saveAll(
            readings.map { r ->
                BetRoundStock(
                    day, r.stock.code, r.stock.name, r.market.name, r.stock.lead, kiwoom.fetchNxtListed(r.stock.code) == true,
                    r.close, r.high, r.low, r.stock.changeRate.toFloat(), r.flows.foreign, r.flows.institution, r.flows.foreign5d, r.flows.institution5d,
                    r.recentHighGap?.toFloat(), r.regularClose, json.writeValueAsString(r.checks.toMap()), r.checks.grade.name,
                    crowd[r.stock.code] ?: 0, pots[r.stock.code] ?: 0,
                )
            },
        )
        dayBets.filter { it.status == ClosingBet.OPEN }.forEach { it.fill(byCode.getValue(it.stockCode).close) }
        rounds.save(BetRound(day, at, json.writeValueAsString(flows), current.players, current.totalMan))
        log.info("종가베팅 체결 {} — {}종목, {}명", day, readings.size, current.players)
        return true
    }

    /** 매도 창이 끝난 종목부터 판다. 판의 모든 종목이 끝나면 true(랭킹 확정). */
    fun settleRound(day: LocalDate, sellDay: LocalDate, at: LocalDateTime): Boolean = inTx { settle(day, sellDay, at) }

    private fun settle(day: LocalDate, sellDay: LocalDate, at: LocalDateTime): Boolean {
        val round = rounds.findById(day).orElse(null) ?: return false
        if (round.settled) return true
        val stocks = roundStocks.findByTradingDay(day)
        for (s in stocks) {
            if (s.sold || at.isBefore(SellWindow.revealAt(sellDay, s.nxt))) continue
            val got = SellWindow.price(kiwoom.fetchHistoricalMinuteCandles(s.stockCode, sellDay), sellDay, s.nxt, s.closePrice)
            // 아직 첫 체결이 없다 — 다음에 다시. 그날 끝까지 없으면(거래정지) 20:00에 매수가로 끝낸다
            if (got.windowStart == null && at.toLocalTime().isBefore(GIVE_UP_AT)) continue
            // 미뤄진 5분이 아직 안 끝났다
            if (got.windowStart != null && at.isBefore(got.windowStart.plus(SellWindow.LENGTH))) continue
            s.sell(got)
            bets.findByTradingDayAndStockCodeAndStatus(day, s.stockCode, ClosingBet.FILLED).forEach { it.settle(got.price) }
        }
        if (stocks.all { it.sold }) round.settle(at)
        return round.settled
    }

    fun results(day: LocalDate): Results = Results(bets.findByTradingDayAndStatus(day, ClosingBet.SETTLED).map { it.result() })

    fun nicknames(userIds: Set<Long>): Map<Long, String> =
        if (userIds.isEmpty()) emptyMap() else players.findAllById(userIds).associate { it.userId to it.nickname }

    fun roundStocks(day: LocalDate): List<BetRoundStock> = roundStocks.findByTradingDay(day)

    fun betRound(day: LocalDate): BetRound? = rounds.findById(day).orElse(null)

    private fun <T> inTx(block: () -> T): T = tx.execute { block() } as T

    private fun regularClose(code: String, on: LocalDate): Long? = regularCloses.get(code to on) {
        if (now().isBefore(on.atTime(Checks.REGULAR_CLOSE))) null else Checks.regularClose(kiwoom.fetchHistoricalMinuteCandles(code, on), on)
    }

    private fun stockFlows(code: String, on: LocalDate): StockFlows {
        val days = stockInvestors.investorDailyHistory(code, FLOW_DAYS)
        val today = days.firstOrNull { it.date == on }
        return StockFlows(today?.foreignMillion ?: 0, today?.institutionMillion ?: 0, days.sumOf { it.foreignMillion }, days.sumOf { it.institutionMillion })
    }

    companion object {
        const val LEADERS_COUNT = 5

        /** 후보 등락률 하한(%) — 주도주 목록 화면의 기본값(+7%)보다 넓게, 오른 종목이면 다 고를 수 있게 */
        const val CANDIDATE_MIN_CHANGE_RATE = 0.0

        /** 최근 고점 60거래일 + 오늘 */
        private const val DAILY_COUNT = 61
        private const val FLOW_DAYS = 5
        private val BANNED = emptyList<String>()

        /** 그날 끝까지 체결이 없으면(거래정지) 이 시각에 매수가로 끝낸다 */
        private val GIVE_UP_AT = Phase.BET_CLOSE

        private fun code(stockCode: String) = stockCode.substringBefore("_")

        private fun poolStock(s: LeadingStockSnapshot, lead: Boolean) = PoolStock(code(s.stockCode), s.stockName, lead, s.currentPrice, s.priceChangeRate)

        /** 시장 막판 — 마감 + 애프터 구간의 외인 + 기관 합(억원). */
        @Suppress("UNCHECKED_CAST")
        fun marketLate(flows: Map<String, Any?>): Map<Market, Long> = listOf(Market.KOSPI, Market.KOSDAQ).associateWith { m ->
            val rows = flows[m.name] as Map<String, List<Number>>
            listOf("foreign", "institution").sumOf { rows.getValue(it)[4].toLong() + rows.getValue(it)[5].toLong() }
        }
    }
}
