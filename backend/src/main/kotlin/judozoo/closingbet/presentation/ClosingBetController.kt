package judozoo.closingbet.presentation

import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import judozoo.auth.domain.CurrentUser
import judozoo.closingbet.application.ClosingBetService
import judozoo.closingbet.domain.BetRejected
import judozoo.closingbet.domain.BetRoundStock
import judozoo.closingbet.domain.Checks
import judozoo.closingbet.domain.ClosingBet
import judozoo.closingbet.domain.NicknameRejected
import judozoo.library.EntityNotFoundException
import judozoo.library.KST
import judozoo.library.web.ApiResponse
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

@RestController
@RequestMapping("/api/closingbet")
class ClosingBetController(private val service: ClosingBetService, private val clock: Clock) {
    private val json = JsonMapper.builder().build()

    data class MyBet(val stockCode: String, val stockName: String, val amountMan: Int, val status: String, val buyPrice: Long?, val shares: Int?) {
        companion object {
            fun of(b: ClosingBet) = MyBet(b.stockCode, b.stockName, b.amountMan, b.status.value, b.buyPrice, b.shares)
        }
    }

    data class Me(val nickname: String?, val nextRenameAt: LocalDateTime?, val bet: MyBet?)

    data class Now(
        val moment: String,
        val bettingDay: LocalDate?,
        val reviewDay: LocalDate?,
        val nextAt: LocalDateTime?,
        val potMan: Long,
        val players: Int,
        val me: Me?,
    )

    data class CheckItem(val nearHigh: Boolean, val foreign: Boolean, val institution: Boolean, val marketLate: Boolean, val recentHigh: Boolean, val afterHold: Boolean) {
        companion object {
            fun of(c: Checks) = CheckItem(c.nearHigh, c.foreign, c.institution, c.marketLate, c.recentHigh, c.afterHold)
        }
    }

    data class StockItem(
        val code: String,
        val name: String,
        val lead: Boolean,
        val price: Long,
        val changeRate: Double,
        val market: String?,
        val nxt: Boolean?,
        val grade: String?,
        val crowd: Int,
        val potMan: Long,
        val high: Long? = null,
        val low: Long? = null,
        val foreign: Long? = null,
        val institution: Long? = null,
        val foreignWeek: Long? = null,
        val institutionWeek: Long? = null,
        val recentHighGap: Double? = null,
        val regularClose: Long? = null,
        val checks: CheckItem? = null,
    )

    data class FeedEntry(val id: Long, val nickname: String, val stockName: String, val amountMan: Int, val at: LocalDateTime)

    data class BetRequest(@field:Pattern(regexp = "\\d{6}") val code: String, val amountMan: Int)

    data class NicknameRequest(@field:Size(max = 40) val nickname: String)

    data class RoundStock(
        val code: String,
        val name: String,
        val lead: Boolean,
        val nxt: Boolean,
        val closePrice: Long,
        val changeRate: Double,
        val market: String,
        val grade: String,
        val crowd: Int,
        val potMan: Long,
        val sellPrice: Double?,
        val rate: Double?,
        val high: Long? = null,
        val low: Long? = null,
        val foreign: Long? = null,
        val institution: Long? = null,
        val foreignWeek: Long? = null,
        val institutionWeek: Long? = null,
        val recentHighGap: Double? = null,
        val regularClose: Long? = null,
        val checks: CheckItem? = null,
    )

    data class RankRow(val rank: Int, val nickname: String, val stockName: String, val pnl: Long, val rate: Double)

    data class MyResult(
        val stockCode: String,
        val stockName: String,
        val amountMan: Int,
        val shares: Int?,
        val buyPrice: Long?,
        val sellPrice: Double?,
        val pnl: Long?,
        val rate: Double?,
        val profitRank: Int?,
        val rateRank: Int?,
    )

    data class Round(
        val day: LocalDate,
        val settled: Boolean,
        val players: Int,
        val potMan: Long,
        val stocks: List<RoundStock>,
        val byProfit: List<RankRow>,
        val byRate: List<RankRow>,
        val market: Any?,
        val me: MyResult?,
    )

    @GetMapping("/now")
    fun now(user: CurrentUser?): ApiResponse<Now> {
        val phase = service.phase(at())
        val stakes = phase.betting?.let(service::stakes)
        val me = user?.let { u ->
            val player = service.findPlayer(u.id)
            Me(player?.nickname, player?.nextRenameAt, phase.betting?.let { service.myBet(u.id, it) }?.let(MyBet::of))
        }
        return ApiResponse.ok(Now(phase.moment.value, phase.betting, phase.review, phase.nextAt, stakes?.totalMan ?: 0, stakes?.players ?: 0, me))
    }

    @GetMapping("/stocks")
    fun stocks(user: CurrentUser?): ApiResponse<List<StockItem>> {
        val day = service.phase(at()).betting ?: return ApiResponse.ok(emptyList())
        val stakes = service.stakes(day)
        val (pots, crowd) = stakes.byStock() to stakes.crowd()
        val cached = service.readings(day)
        return ApiResponse.ok(
            service.pool().map { s ->
                val r = cached.readings[s.code]
                val item = StockItem(
                    s.code, s.name, s.lead, s.price, s.changeRate, r?.market?.name, cached.nxt[s.code], r?.checks?.grade?.name,
                    crowd[s.code] ?: 0, pots[s.code] ?: 0,
                )
                if (user == null || r == null) item
                else item.copy(
                    high = r.high, low = r.low, foreign = r.flows.foreign, institution = r.flows.institution,
                    foreignWeek = r.flows.foreign5d, institutionWeek = r.flows.institution5d,
                    recentHighGap = r.recentHighGap, regularClose = r.regularClose, checks = CheckItem.of(r.checks),
                )
            },
        )
    }

    @GetMapping("/market")
    fun market(@Suppress("UNUSED_PARAMETER") user: CurrentUser): ApiResponse<Map<String, Any?>?> =
        ApiResponse.ok(service.phase(at()).betting?.let { service.readings(it).flows.published() })

    @GetMapping("/feed")
    fun feed(@RequestParam(defaultValue = "0") after: Long): ApiResponse<List<FeedEntry>> {
        if (after < 0) throw org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER")
        val day = service.phase(at()).betting ?: return ApiResponse.ok(emptyList())
        return ApiResponse.ok(service.feed(day, after).map { FeedEntry(it.id, it.nickname, it.stockName, it.amountMan, it.at) })
    }

    @PutMapping("/bet")
    fun bet(@Valid @RequestBody body: BetRequest, user: CurrentUser): ApiResponse<MyBet> =
        ApiResponse.ok(MyBet.of(service.place(user.id, body.code, body.amountMan, at())))

    @DeleteMapping("/bet")
    fun cancel(user: CurrentUser): ApiResponse<Nothing?> {
        service.cancel(user.id, at())
        return ApiResponse.ok(null)
    }

    @PutMapping("/me/nickname")
    fun nickname(@Valid @RequestBody body: NicknameRequest, user: CurrentUser): ApiResponse<Me> {
        val player = service.rename(user.id, body.nickname, at())
        return ApiResponse.ok(Me(player.nickname, player.nextRenameAt, null))
    }

    @GetMapping("/rounds/{day}")
    fun round(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) day: LocalDate, user: CurrentUser?): ApiResponse<Round> {
        val round = service.betRound(day) ?: throw EntityNotFoundException("판이 없다: $day")
        val stocks = service.roundStocks(day)
        val names = stocks.associate { it.stockCode to it.stockName }
        val results = service.results(day)
        val nicks = service.nicknames(results.ranked(false).map { it.result.userId }.toSet())
        fun rows(byRate: Boolean) = results.top(byRate).map {
            RankRow(it.rank, nicks[it.result.userId] ?: "?", names[it.result.stockCode] ?: it.result.stockCode, it.result.outcome.pnl, it.result.outcome.rate)
        }
        val me = user?.let { u ->
            service.myBet(u.id, day)?.let { b ->
                MyResult(
                    b.stockCode, b.stockName, b.amountMan, b.shares, b.buyPrice, b.sellPrice, b.pnl, b.rate,
                    results.rankOf(u.id, false)?.rank, results.rankOf(u.id, true)?.rank,
                )
            }
        }
        return ApiResponse.ok(
            Round(
                day, round.settled, round.players, round.potMan, stocks.map { roundStock(it, user != null) }, rows(false), rows(true),
                if (user != null) json.readValue<Any>(round.marketJson) else null, me,
            ),
        )
    }

    @ExceptionHandler(BetRejected::class)
    fun betRejected(e: BetRejected) = rejected("BET_REJECTED", e)

    @ExceptionHandler(NicknameRejected::class)
    fun nicknameRejected(e: NicknameRejected) = rejected("NICKNAME_REJECTED", e)

    private fun rejected(code: String, e: RuntimeException): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("code" to code, "status" to 409, "data" to mapOf("message" to e.message)))

    private fun roundStock(s: BetRoundStock, member: Boolean): RoundStock {
        val item = RoundStock(
            s.stockCode, s.stockName, s.lead, s.nxt, s.closePrice, s.changeRate, s.market.name, s.grade.name, s.crowd, s.potMan, s.sellPrice, s.rate,
        )
        if (!member) return item
        return item.copy(
            high = s.highPrice, low = s.lowPrice, foreign = s.foreignNet, institution = s.institutionNet,
            foreignWeek = s.foreign5d, institutionWeek = s.institution5d, recentHighGap = s.recentHighGap, regularClose = s.regularClose,
            checks = CheckItem.of(s.checks),
        )
    }

    private fun at(): LocalDateTime = LocalDateTime.now(clock.withZone(KST))
}
