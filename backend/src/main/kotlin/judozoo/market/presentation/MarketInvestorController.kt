package judozoo.market.presentation

import judozoo.library.KST
import judozoo.library.web.ApiResponse
import judozoo.market.application.MarketInvestors
import judozoo.stock.domain.Market
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDate

@RestController
@RequestMapping("/api/market")
class MarketInvestorController(private val investors: MarketInvestors, private val clock: Clock) {
    data class SessionNets(val date: LocalDate, val sessions: List<MarketInvestors.SessionNet>)

    data class FuturesSessions(val date: LocalDate, val sessions: List<MarketInvestors.FuturesSessionNet>)

    @GetMapping("/investor/today")
    fun today() = ApiResponse.ok(investors.todayNets())

    @GetMapping("/{market}/investor/daily")
    fun daily(@PathVariable market: Market, @RequestParam(defaultValue = "10") count: Int) =
        ApiResponse.ok(investors.investorDailyHistory(market, count))

    @GetMapping("/{market}/investor/sessions")
    fun sessions(@PathVariable market: Market, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?): ApiResponse<SessionNets> {
        val (used, sessions) = investors.investorSessions(market, date ?: LocalDate.now(clock.withZone(KST)))
        return ApiResponse.ok(SessionNets(used, sessions))
    }

    @GetMapping("/futures/{market}/investor/daily")
    fun futuresDaily(@PathVariable market: Market, @RequestParam(defaultValue = "10") count: Int) =
        ApiResponse.ok(investors.futuresInvestorDailyHistory(market, count))

    @GetMapping("/futures/{market}/investor/sessions")
    fun futuresSessions(@PathVariable market: Market, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?): ApiResponse<FuturesSessions> {
        val (used, sessions) = investors.futuresInvestorSessions(market, date ?: LocalDate.now(clock.withZone(KST)))
        return ApiResponse.ok(FuturesSessions(used, sessions))
    }
}
