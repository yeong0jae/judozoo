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

/**
 * 국내 주도주 실시간 로그 API — 종목 시그널 / 시장 시그널 / 시장 수급 / 지수 분봉. 폴러가 적재한 것을 읽기만 한다.
 *
 * 종목 시그널만 공개(미리보기로 잘라서), 나머지는 로그인 뒤다([judozoo.auth.presentation.Gate]).
 */
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

    /**
     * 그날 발생한 시그널 전이를 최신순으로.
     *
     * **미로그인이면 등락률 [PREVIEW_MIN_CHANGE_RATE]% 이상 중 최신 [PREVIEW_COUNT]건만 내려간다** —
     * 화면에서 자르면 나머지가 개발자도구로 읽힌다. `totalCount`는 건수로 자르기 전의 수다.
     */
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

    /** 투자자 순매수 단계·흐름 전환 + 지수 반등·꺾임. 실시간 로그가 종목 시그널과 합쳐 보여준다. */
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

    /** [at]을 주면 그 시각 이하 가장 가까운 스냅샷(시그널 발생 시점 값), 안 주면 가장 최근 스냅샷. 지수값·등락률은 시그널 시점 조회에서만 비운다. */
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

    /** 실시간 로그에서 지수 행 선택 시 우측 차트용. 기준일 포함 직전 거래일까지 2거래일치. 모르는 시장이면 500(Python과 같다). */
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
        /** 미로그인 미리보기로 내려보내는 최신 시그널 건수. */
        const val PREVIEW_COUNT = 3

        /**
         * 미로그인 미리보기 등락률 하한(%). 감시 풀이 -12%까지 넓어 그냥 자르면 급락주 전이로 찰 수 있다.
         * 0은 "내린 종목만 뺀다"이고 로그인 화면의 등락률 필터 기본값과 같다.
         */
        const val PREVIEW_MIN_CHANGE_RATE = 0.0
    }
}
