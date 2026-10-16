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

/** 시황 API 중 투자자 수급. 모두 로그인 뒤다 — 이 서비스가 가공한 값이다. 응답은 도메인 값 객체 그대로 — 필드 이름이 Python 별칭과 같다. */
@RestController
@RequestMapping("/api/market")
class MarketInvestorController(private val investors: MarketInvestors, private val clock: Clock) {

    /** 조회에 **실제로 쓴 날짜**를 함께 싣는다 — 공휴일이면 직전 거래일로 물러나므로, 요청한 날짜로 라벨을 붙이면 거짓말이 된다. */
    data class SessionNets(val date: LocalDate, val sessions: List<MarketInvestors.SessionNet>)

    data class FuturesSessions(val date: LocalDate, val sessions: List<MarketInvestors.FuturesSessionNet>)

    /** 첫 화면 "오늘의 수급" — 코스피·코스닥 현물과 두 지수선물의 당일 누적 순매수. */
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
