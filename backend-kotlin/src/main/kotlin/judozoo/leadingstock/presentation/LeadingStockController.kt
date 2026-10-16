package judozoo.leadingstock.presentation

import judozoo.auth.domain.CurrentUser
import judozoo.leadingstock.application.LeadingStockService
import judozoo.leadingstock.domain.FilterResult
import judozoo.leadingstock.domain.LeadingStockCriteria
import judozoo.leadingstock.domain.LeadingStockSnapshot
import judozoo.library.KST
import judozoo.library.web.ApiResponse
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
 * 국내 주도주 API — 후보 / 첫 화면 주도주 / 저항·지지 / 종목 상세·분봉·일봉.
 *
 * 후보·주도주·저항·지지는 공개, 종목 상세 이하는 로그인 뒤다([judozoo.auth.presentation.Gate]).
 * 시그널 로그·시장 시그널·시장 수급·지수 분봉은 아직 Python이다 — Python 폴러가 쓰는 표를 읽는다.
 */
@RestController
@RequestMapping("/api/leading-stocks")
class LeadingStockController(private val service: LeadingStockService, private val clock: Clock) {

    /** Python 응답과 같은 필드만 — 도메인 객체를 그대로 내보내면 계산 속성까지 실린다. */
    data class CandidateStockItem(
        val rank: Int,
        val stockCode: String,
        val stockName: String,
        val currentPrice: Long,
        val priceChangeRate: Double,
        val accumulatedTradingValue: Long,
    ) {
        companion object {
            fun of(rank: Int, s: LeadingStockSnapshot) =
                CandidateStockItem(rank, s.stockCode, s.stockName, s.currentPrice, s.priceChangeRate, s.accumulatedTradingValue)
        }
    }

    data class CandidateStocks(val queriedAt: LocalDateTime, val totalCount: Int, val stocks: List<CandidateStockItem>)

    /** 상한가 칩 한 장. **이름만 있으면 되는 자리**라 시세를 싣지 않는다. */
    data class LimitUpItem(val stockCode: String, val stockName: String)

    /** 둘을 한 응답에 담는다 — 따로 부르면 그 사이 풀이 갱신돼 탑5와 상한가가 다른 순간을 말할 수 있다. */
    data class Leaders(val leaders: List<CandidateStockItem>, val limitUps: List<LimitUpItem>)

    data class BreakoutRadarItem(
        val stockCode: String,
        val stockName: String,
        val currentPrice: Long,
        val priceChangeRate: Double,
        val peakPrice: Long,
        val peakAt: LocalDateTime,
        val gapRate: Double,
        val troughPrice: Long?,
        val troughAt: LocalDateTime?,
        val supportGapRate: Double?,
        val tradingValue: Long,
    )

    data class BreakoutRadar(val queriedAt: LocalDateTime, val totalCount: Int, val stocks: List<BreakoutRadarItem>)

    data class StockDetail(
        val stockCode: String,
        val stockName: String,
        val currentPrice: Long,
        val priceChangeRate: Double,
        val relativeVolume: Double?,
        /** 소속 시장 — 카탈로그에 없으면 null(화면은 그때 아무것도 적지 않는다) */
        val market: String?,
        val filterResults: List<FilterResult>,
        val openingPrice: Long,
        val highPrice: Long,
        val lowPrice: Long,
        val previousClose: Long,
        /** 누적 거래대금(원) — 거래대금 순위 밖이면 받아오지 않아 null */
        val tradingValue: Long?,
    )

    data class MinuteCandleItem(
        val time: LocalDateTime,
        val open: Long,
        val high: Long,
        val low: Long,
        val close: Long,
        val volume: Long,
        val tradingValue: Long,
    )

    data class DailyCandleItem(val date: String, val open: Long, val high: Long, val low: Long, val close: Long, val volume: Long)

    /** 1단계 후보 — 거래대금 순위 + 등락률 필터만 통과. 등락률 기준은 -12~7 안으로 잘라 쓴다. 안 주면 7. */
    @GetMapping("/candidates")
    fun candidates(@RequestParam(required = false) minChangeRate: Int?): ApiResponse<CandidateStocks> {
        val rate = minChangeRate?.coerceIn(MIN_CHANGE_RATE, MAX_CHANGE_RATE)?.toDouble() ?: LeadingStockCriteria().minDailyPriceChangeRate
        val items = service.findCandidates(rate).mapIndexed { i, s -> CandidateStockItem.of(i + 1, s) }
        return ApiResponse.ok(CandidateStocks(now(), items.size, items))
    }

    /**
     * 첫 화면용 — 거래대금·등락률이 함께 높은 상위 [LEADERS_COUNT]개 + 후보 풀의 상한가.
     *
     * **등락률 파라미터를 받지 않는다** — 보는 사람의 기준과 무관하게 같은 답을 보여야 한다.
     */
    @GetMapping("/leaders")
    fun leaders() = ApiResponse.ok(
        Leaders(
            service.findLeaders(LEADERS_COUNT).mapIndexed { i, s -> CandidateStockItem.of(i + 1, s) },
            service.findLimitUps().map { LimitUpItem(it.stockCode, it.stockName) },
        ),
    )

    /**
     * 감시 풀을 돌파선(최근 3거래일 고가) 근접 순으로. 눌림선(최근 3거래일 저가)도 함께 싣는다.
     *
     * **등락률 파라미터를 받지 않는다** — 눌림은 내린 종목에서 나오는 신호다. [mode]가 기준 선을 정하고,
     * 그 선에서 [RADAR_NEAR_RATE]% 이내인 종목만 가까운 순으로 담는다.
     *
     * **미로그인이면 상위 [RADAR_PREVIEW_COUNT]개만 내려간다.** `totalCount`는 건수로 자르기 전의 수라 받는 쪽이 잘렸는지 안다.
     */
    @GetMapping("/breakout-radar")
    fun breakoutRadar(@RequestParam(defaultValue = "resistance") mode: String, user: CurrentUser?): ApiResponse<BreakoutRadar> {
        val support = mode == "support"
        fun line(item: BreakoutRadarItem) = if (support) item.supportGapRate else item.gapRate
        val near = service.breakoutRadar()
            .map { (s, peak, trough) ->
                BreakoutRadarItem(
                    s.stockCode, s.stockName, s.currentPrice, s.priceChangeRate, peak.peakPrice, peak.peakAt, peak.gapRate,
                    trough?.troughPrice, trough?.troughAt, trough?.gapRate, s.accumulatedTradingValue,
                )
            }
            .filter { item -> line(item)?.let { it <= RADAR_NEAR_RATE } ?: false }
            .sortedBy { line(it) }
        val items = if (user == null) near.take(RADAR_PREVIEW_COUNT) else near
        return ApiResponse.ok(BreakoutRadar(now(), near.size, items))
    }

    /** 상세 캔들차트용 — 기준일 기준 최근 3거래일. */
    @GetMapping("/candidates/{stockCode}/minute-candles")
    fun minuteCandles(@PathVariable stockCode: String, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?) =
        ApiResponse.ok(
            service.minuteCandles(stockCode, date ?: today())
                .map { MinuteCandleItem(it.dateTime, it.openPrice, it.highPrice, it.lowPrice, it.closePrice, it.volume, it.tradingValue) },
        )

    @GetMapping("/candidates/{stockCode}/daily-candles")
    fun dailyCandles(@PathVariable stockCode: String, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?) =
        ApiResponse.ok(
            service.dailyCandles(stockCode, date ?: today())
                .map { DailyCandleItem(it.date.toString(), it.openPrice, it.highPrice, it.lowPrice, it.closePrice, it.volume) },
        )

    /** 전체 필터 평가 결과 + 상대거래량. 순서는 판별력이 큰 것부터. */
    @GetMapping("/candidates/{stockCode}")
    fun detail(@PathVariable stockCode: String): ApiResponse<StockDetail> {
        val (s, filters, relativeVolume, market) = service.evaluate(stockCode)
        return ApiResponse.ok(
            StockDetail(
                s.stockCode, s.stockName, s.currentPrice, s.priceChangeRate, relativeVolume, market?.name, filters,
                s.openingPrice, s.highPrice, s.lowPrice, s.previousClose, s.accumulatedTradingValue.takeIf { it != 0L },
            ),
        )
    }

    private fun now(): LocalDateTime = LocalDateTime.now(clock.withZone(KST))

    private fun today(): LocalDate = now().toLocalDate()

    companion object {
        private const val MIN_CHANGE_RATE = -12
        private const val MAX_CHANGE_RATE = 7

        /** 첫 화면 주도주 칸 수. 늘리려면 여기만 고친다. */
        const val LEADERS_COUNT = 5

        /** 미로그인 미리보기로 내려보내는 눌림·돌파 종목 수. 시그널과 같은 수로 맞춘다. */
        const val RADAR_PREVIEW_COUNT = 3

        /** 눌림·돌파로 볼 근접 범위(%). 화면이 색을 넣는 기준(`BreakoutRadarPage`의 `NEAR`)과 **같은 값이어야 한다**. */
        const val RADAR_NEAR_RATE = 3.0
    }
}
