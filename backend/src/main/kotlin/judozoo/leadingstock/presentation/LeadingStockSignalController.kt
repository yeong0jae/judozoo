package judozoo.leadingstock.presentation

import judozoo.auth.domain.CurrentUser
import judozoo.leadingstock.application.LeadingStockSignals
import judozoo.library.KST
import judozoo.library.web.ApiResponse
import judozoo.stock.domain.Market
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

@RestController
@RequestMapping("/api/leading-stocks")
class LeadingStockSignalController(private val signals: LeadingStockSignals, private val clock: Clock) {
    data class SignalEventItem(
        val occurredAt: LocalDateTime,
        val stockCode: String,
        val stockName: String,
        val eventType: String,
        val currentPrice: Long,
        val priceChangeRate: Double,
        val tradingValue: Long,
        val gapRate: Double?,
        val spikeRatio: Double?,
        val minuteTradingValue: Long?,
        val spikeDirection: String?,
        val ma: Long?,
    )

    data class SignalEvents(val date: LocalDate, val totalCount: Int, val events: List<SignalEventItem>)

    data class MarketSignalEventItem(
        val occurredAt: LocalDateTime,
        val kind: String,
        val market: String,
        val side: String,
        val investor: String?,
        val level: Int?,
        val thresholdEok: Long?,
        val netAmountEok: Long?,
        val extremeAmountEok: Long?,
        val indexValue: Double?,
        val changeRate: Double?,
    )

    data class MarketSignalEvents(val date: LocalDate, val totalCount: Int, val events: List<MarketSignalEventItem>)

    data class MarketInvestorNetBuyItem(
        val market: String,
        val foreignEok: Long,
        val institutionEok: Long,
        val individualEok: Long,
        val otherCorpEok: Long,
        val financialInvestmentEok: Long,
        val trustEok: Long,
        val pensionFundEok: Long,
        val privateEquityEok: Long,
        val insuranceEok: Long,
        val bankEok: Long,
        val indexValue: Double?,
        val changeRate: Double?,
    )

    data class IndexMinuteCandleItem(val time: LocalDateTime, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Long)

    @GetMapping("/signal-events")
    fun signalEvents(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?,
        user: CurrentUser?,
    ): ApiResponse<SignalEvents> {
        val day = date ?: today()
        val visible = signals.signalEventsOn(day)
            .filter { user != null || it.priceChangeRate >= PREVIEW_MIN_CHANGE_RATE }
            .map {
                SignalEventItem(
                    it.occurredAt, it.stockCode, it.stockName, it.eventType, it.currentPrice, it.priceChangeRate, it.tradingValue,
                    it.gapRate, it.spikeRatio, it.minuteTradingValue, it.spikeDirection?.name, it.ma,
                )
            }
        return ApiResponse.ok(SignalEvents(day, visible.size, if (user == null) visible.take(PREVIEW_COUNT) else visible))
    }

    @GetMapping("/market-signal-events")
    fun marketSignalEvents(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?): ApiResponse<MarketSignalEvents> {
        val day = date ?: today()
        val items = signals.marketEventsOn(day).map {
            MarketSignalEventItem(
                it.occurredAt, it.kind, it.market.name, it.side.name, it.investor?.name, it.level, it.thresholdEok,
                it.netAmountEok, it.extremeAmountEok, it.indexValue, it.changeRate,
            )
        }
        return ApiResponse.ok(MarketSignalEvents(day, items.size, items))
    }

    @GetMapping("/market/investor-net-buy")
    fun investorNetBuy(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) at: LocalDateTime?) = ApiResponse.ok(
        signals.investorNetBuy(at, now()).map { (market, s) ->
            MarketInvestorNetBuyItem(
                market.name, s.foreignEok, s.institutionEok, s.individualEok, s.otherCorpEok, s.financialInvestmentEok, s.trustEok,
                s.pensionFundEok, s.privateEquityEok, s.insuranceEok, s.bankEok,
                s.indexValue.takeIf { at == null }, s.changeRate.takeIf { at == null },
            )
        },
    )

    @GetMapping("/index/{market}/minute-candles")
    fun indexMinuteCandles(
        @PathVariable market: String,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?,
    ) = ApiResponse.ok(
        signals.indexMinuteCandles(Market.valueOf(market.uppercase()), date ?: today())
            .map { IndexMinuteCandleItem(it.minute, it.open, it.high, it.low, it.close, it.volume) },
    )

    private fun now(): LocalDateTime = LocalDateTime.now(clock.withZone(KST))

    private fun today(): LocalDate = now().toLocalDate()

    companion object {
        const val PREVIEW_COUNT = 3

        const val PREVIEW_MIN_CHANGE_RATE = 0.0
    }
}
