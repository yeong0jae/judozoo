package judozoo.platform.toss

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import kotlin.math.floor

/**
 * 토스 Market Indicators — 투자자별 매매대금 + 캔들(OHLCV), 코스피/코스닥.
 *
 * 투자자별은 매수/매도 거래대금(원)을 주므로 **순매수 = 매수 − 매도**로 계산해 억원으로 환산한다.
 * 기관은 7개 세부(연기금·투신·금융투자·사모·보험·은행·기타금융)를 함께 준다.
 * 캔들은 실제 OHLCV라 키움 지수값 합성과 달리 정직한 시가·고가·저가를 준다.
 */
@Component
class TossMarketIndicator(private val client: TossClient) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class InstitutionBreakdown(
        val pensionFundEok: Long,
        val trustEok: Long,
        val financialInvestmentEok: Long,
        val privateEquityEok: Long,
        val insuranceEok: Long,
        val bankEok: Long,
        val otherFinanceEok: Long,
    )

    data class MarketInvestorRecord(
        val date: LocalDate,
        /** KST 벽시계 */
        val sourceUpdatedAt: LocalDateTime?,
        val individualNetEok: Long,
        val foreignNetEok: Long,
        val institutionNetEok: Long,
        val otherCorpNetEok: Long,
        val breakdown: InstitutionBreakdown,
    )

    data class TossCandle(
        /** 응답 오프셋의 벽시계 */
        val timestamp: LocalDateTime,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double,
    )

    /** [nextBefore]는 다음 페이지 커서(직전 응답 값 그대로) — 더 이전 페이지가 없으면 null. */
    data class CandlesPage(val candles: List<TossCandle>, val nextBefore: String?)

    /** [symbol] "KOSPI"/"KOSDAQ". 최신순. 실패 시 빈 목록. */
    fun fetchInvestorTrading(symbol: String, interval: String = "1d", count: Int = 10, until: LocalDate? = null): List<MarketInvestorRecord> =
        try {
            val params = buildMap<String, Any> {
                put("interval", interval)
                put("count", count)
                until?.let { put("until", it.toString()) }
            }
            client.get("/api/v1/market-indicators/$symbol/investor-trading", params)["result"]?.get("records")
                ?.values().orEmpty().mapNotNull(::toRecord)
        } catch (e: Exception) {
            log.error("Toss 투자자별 매매대금 조회 실패 symbol={} interval={}", symbol, interval, e)
            emptyList()
        }

    /** 캔들(OHLCV). [interval] 1m/1d, [count] 최대 200. 최신순. 실패 시 빈 페이지. 페이지마다 지수 캔들 한도를 얻는다. */
    fun fetchCandles(symbol: String, interval: String, count: Int, before: String? = null): CandlesPage = try {
        val params = buildMap<String, Any> {
            put("interval", interval)
            put("count", count)
            before?.let { put("before", it) }
        }
        val result = client.get("/api/v1/market-indicators/$symbol/candles", params, client.indicatorChartLimiter)["result"]
        CandlesPage(
            candles = result?.get("candles")?.values().orEmpty().mapNotNull(::toCandle),
            nextBefore = result?.get("nextBefore")?.takeUnless { it.isNull }?.asString(),
        )
    } catch (e: Exception) {
        log.error("Toss 캔들 조회 실패 symbol={} interval={}", symbol, interval, e)
        CandlesPage(emptyList(), null)
    }

    private fun toRecord(item: JsonNode): MarketInvestorRecord? {
        val on = item.text("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val institution = item["institution"]
        val breakdown = institution?.get("breakdown")
        fun part(key: String) = netEok(breakdown?.get(key))
        return MarketInvestorRecord(
            date = on,
            sourceUpdatedAt = parseWallClock(item.text("updatedAt")),
            individualNetEok = netEok(item["individual"]),
            foreignNetEok = netEok(item["foreigner"]),
            // 기관 합계는 breakdown이 아니라 institution 자신의 매수/매도로 계산한다
            institutionNetEok = netEok(institution),
            otherCorpNetEok = netEok(item["otherCorporation"]),
            breakdown = InstitutionBreakdown(
                pensionFundEok = part("pensionFund"),
                trustEok = part("trust"),
                financialInvestmentEok = part("financialInvestment"),
                privateEquityEok = part("privateEquityFund"),
                insuranceEok = part("insurance"),
                bankEok = part("bank"),
                otherFinanceEok = part("otherFinancialInstitution"),
            ),
        )
    }

    private fun toCandle(item: JsonNode): TossCandle? {
        val ts = parseWallClock(item.text("timestamp")) ?: return null
        // OHLC 중 하나라도 없으면 캔들이 아니다
        val (open, high, low, close) = listOf("openPrice", "highPrice", "lowPrice", "closePrice")
            .map { item.text(it)?.toDoubleOrNull() ?: return null }
        return TossCandle(ts, open, high, low, close, item.text("volume")?.toDoubleOrNull() ?: 0.0)
    }

    companion object {
        private const val EOK = 100_000_000.0

        /** 순매수(억) = (매수 − 매도) / 1억. `.5`는 항상 위로 올린다(floor(x + 0.5)) — Python과 같은 값이다. */
        fun netEok(amount: JsonNode?): Long {
            if (amount == null || amount.isNull || amount.isEmpty) return 0
            val buy = amount.text("buyAmount")?.toDoubleOrNull() ?: 0.0
            val sell = amount.text("sellAmount")?.toDoubleOrNull() ?: 0.0
            return floor((buy - sell) / EOK + 0.5).toLong()
        }

        private fun JsonNode.text(field: String): String? = get(field)?.takeUnless { it.isNull }?.asString()?.trim()

        /** `"2026-06-11T18:10:00+09:00"` → 그 오프셋의 벽시계. 오프셋이 없으면 그대로. 실패 시 null. */
        private fun parseWallClock(value: String?): LocalDateTime? {
            if (value.isNullOrEmpty()) return null
            return runCatching { OffsetDateTime.parse(value).toLocalDateTime() }.getOrNull()
                ?: runCatching { LocalDateTime.parse(value) }.getOrNull()
        }
    }
}
