package judozoo.platform.toss

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import kotlin.math.floor

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
        val sourceUpdatedAt: LocalDateTime?,
        val individualNetEok: Long,
        val foreignNetEok: Long,
        val institutionNetEok: Long,
        val otherCorpNetEok: Long,
        val breakdown: InstitutionBreakdown,
    )

    data class TossCandle(
        val timestamp: LocalDateTime,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double,
    )

    data class CandlesPage(val candles: List<TossCandle>, val nextBefore: String?)

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
        val (open, high, low, close) = listOf("openPrice", "highPrice", "lowPrice", "closePrice")
            .map { item.text(it)?.toDoubleOrNull() ?: return null }
        return TossCandle(ts, open, high, low, close, item.text("volume")?.toDoubleOrNull() ?: 0.0)
    }

    companion object {
        private const val EOK = 100_000_000.0

        fun netEok(amount: JsonNode?): Long {
            if (amount == null || amount.isNull || amount.isEmpty) return 0
            val buy = amount.text("buyAmount")?.toDoubleOrNull() ?: 0.0
            val sell = amount.text("sellAmount")?.toDoubleOrNull() ?: 0.0
            return floor((buy - sell) / EOK + 0.5).toLong()
        }

        private fun JsonNode.text(field: String): String? = get(field)?.takeUnless { it.isNull }?.asString()?.trim()

        private fun parseWallClock(value: String?): LocalDateTime? {
            if (value.isNullOrEmpty()) return null
            return runCatching { OffsetDateTime.parse(value).toLocalDateTime() }.getOrNull()
                ?: runCatching { LocalDateTime.parse(value) }.getOrNull()
        }
    }
}
