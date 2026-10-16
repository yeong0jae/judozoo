package judozoo.market.presentation

import judozoo.library.KST
import judozoo.library.web.ApiResponse
import judozoo.market.application.MarketInvestors
import judozoo.market.domain.FuturesNets
import judozoo.market.domain.InvestorSessions
import judozoo.market.domain.Nets
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
    data class SessionItem<N>(val name: String, val time: String, val nets: N?, val delta: N?) {
        companion object {
            fun <N> of(net: InvestorSessions.Net<N>) = SessionItem(net.name, "${net.session.start}~${net.session.end}", net.nets, net.delta)
        }
    }

    data class SessionNets<N>(val date: LocalDate, val sessions: List<SessionItem<N>>) {
        companion object {
            fun <N> of(found: InvestorSessions<N>) = SessionNets(found.date, found.sessions.map { SessionItem.of(it) })
        }
    }

    @GetMapping("/investor/today")
    fun today() = ApiResponse.ok(investors.todayNets())

    @GetMapping("/{market}/investor/daily")
    fun daily(@PathVariable market: Market, @RequestParam(defaultValue = "10") count: Int) =
        ApiResponse.ok(investors.investorDailyHistory(market, count))

    @GetMapping("/{market}/investor/sessions")
    fun sessions(@PathVariable market: Market, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?): ApiResponse<SessionNets<Nets>> =
        ApiResponse.ok(SessionNets.of(investors.investorSessions(market, date ?: LocalDate.now(clock.withZone(KST)))))

    @GetMapping("/futures/{market}/investor/daily")
    fun futuresDaily(@PathVariable market: Market, @RequestParam(defaultValue = "10") count: Int) =
        ApiResponse.ok(investors.futuresInvestorDailyHistory(market, count))

    @GetMapping("/futures/{market}/investor/sessions")
    fun futuresSessions(@PathVariable market: Market, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?): ApiResponse<SessionNets<FuturesNets>> =
        ApiResponse.ok(SessionNets.of(investors.futuresInvestorSessions(market, date ?: LocalDate.now(clock.withZone(KST)))))
}
