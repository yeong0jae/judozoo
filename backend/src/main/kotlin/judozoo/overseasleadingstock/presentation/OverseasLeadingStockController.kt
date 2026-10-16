package judozoo.overseasleadingstock.presentation

import judozoo.library.web.ApiResponse
import judozoo.overseasleadingstock.application.OverseasLeadingStockService
import judozoo.overseasleadingstock.domain.FilterResult
import judozoo.overseasleadingstock.domain.OverseasStockRank
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

/** 해외 주도주 API — 랭킹 / 종목 상세 / 분봉 / 일봉. 목록·주도주는 공개, 종목 상세 이하는 로그인 뒤다. */
@RestController
@RequestMapping("/api/overseas-leading-stocks")
class OverseasLeadingStockController(private val service: OverseasLeadingStockService) {

    /** Python 응답과 같은 필드만 — 도메인 객체를 그대로 내보내면 계산 속성까지 실린다. */
    data class RankItem(
        val rank: Int,
        val exchange: String,
        val symbol: String,
        val name: String,
        val ename: String,
        val price: Double,
        val diff: Double,
        val rate: Double,
        val tradingValue: Double,
    ) {
        companion object {
            fun of(r: OverseasStockRank) = RankItem(r.rank, r.exchange, r.symbol, r.name, r.ename, r.price, r.diff, r.rate, r.tradingValue)
        }
    }

    data class StockDetail(
        val exchange: String,
        val symbol: String,
        val name: String,
        val ename: String,
        val rank: Int,
        val price: Double,
        val diff: Double,
        val rate: Double,
        val tradingValue: Double,
        val marketCap: Long?,
        val filterResults: List<FilterResult>,
    )

    /** 가격은 달러(소수), time은 한국 벽시계. 국내 분봉과 같은 JSON 구조. */
    data class MinuteCandleItem(
        val time: LocalDateTime,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Long,
        val tradingValue: Double,
    )

    data class DailyCandleItem(val date: String, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Long)

    /** 나스닥·뉴욕·아멕스 통합 거래대금 상위. 등락률 기준은 -12~7 안으로 잘라 쓴다. 안 주면 7. */
    @GetMapping("/candidates")
    fun candidates(@RequestParam(required = false) minChangeRate: Int?): ApiResponse<List<RankItem>> {
        val rate = minChangeRate?.coerceIn(MIN_CHANGE_RATE, MAX_CHANGE_RATE)?.toDouble() ?: DEFAULT_MIN_CHANGE_RATE
        return ApiResponse.ok(service.candidates(rate).map(RankItem::of))
    }

    /** 첫 화면용 — 거래대금·등락률이 함께 높은 상위 [LEADERS_COUNT]개. */
    @GetMapping("/leaders")
    fun leaders(): ApiResponse<List<RankItem>> = ApiResponse.ok(service.leaders(LEADERS_COUNT).map(RankItem::of))

    /** 종목 상세 — 필터 평가. 거래소·심볼은 대문자로 맞춰 찾는다. */
    @GetMapping("/{exchange}/{symbol}")
    fun detail(@PathVariable exchange: String, @PathVariable symbol: String): ApiResponse<StockDetail> {
        val (stock, marketCap, filters) = service.evaluate(exchange.uppercase(), symbol.uppercase())
        return ApiResponse.ok(
            StockDetail(
                stock.exchange, stock.symbol, stock.name, stock.ename, stock.rank, stock.price, stock.diff, stock.rate,
                stock.tradingValue, marketCap, filters,
            ),
        )
    }

    @GetMapping("/{exchange}/{symbol}/minute-candles")
    fun minuteCandles(@PathVariable exchange: String, @PathVariable symbol: String) = ApiResponse.ok(
        service.minuteCandles(exchange.uppercase(), symbol.uppercase())
            .map { MinuteCandleItem(it.dateTime, it.open, it.high, it.low, it.close, it.volume, it.tradingValue) },
    )

    @GetMapping("/{exchange}/{symbol}/daily-candles")
    fun dailyCandles(@PathVariable exchange: String, @PathVariable symbol: String) = ApiResponse.ok(
        service.dailyCandles(exchange.uppercase(), symbol.uppercase())
            .map { DailyCandleItem(it.date.toString(), it.open, it.high, it.low, it.close, it.volume) },
    )

    companion object {
        private const val MIN_CHANGE_RATE = -12
        private const val MAX_CHANGE_RATE = 7
        private const val DEFAULT_MIN_CHANGE_RATE = 7.0

        /** 첫 화면 주도주 칸 수. 국내와 같은 값 — 두 카드가 나란히 서는 자리라 한쪽만 길면 눈에 띈다. */
        const val LEADERS_COUNT = 5
    }
}
