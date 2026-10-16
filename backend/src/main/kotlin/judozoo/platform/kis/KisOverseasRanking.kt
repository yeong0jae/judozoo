package judozoo.platform.kis

import judozoo.overseasleadingstock.domain.OverseasStockRank
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode

@Component
class KisOverseasRanking(private val client: KisClient) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun fetchTradingValueRanking(excd: String): List<OverseasStockRank> {
        log.info("KIS 해외주식 거래대금순위 조회: excd={}", excd)
        val response = client.get(
            "/uapi/overseas-stock/v1/ranking/trade-pbmn", "HHDFS76320010",
            mapOf("KEYB" to "", "AUTH" to "", "EXCD" to excd, "NDAY" to "0", "VOL_RANG" to "0", "PRC1" to "", "PRC2" to ""),
        )
        if (response.status !in 200..299) throw IllegalStateException("KIS 거래대금순위 HTTP ${response.status} (excd=$excd)")
        if (!response.ok) throw IllegalStateException("KIS 거래대금순위 오류: ${response.msg1} (excd=$excd)")
        return response.body!!["output2"]?.values().orEmpty().map(::toRank)
    }

    private fun toRank(row: JsonNode): OverseasStockRank {
        fun text(key: String) = row[key]?.takeUnless { it.isNull }?.asString()?.trim().orEmpty()
        fun number(key: String) = text(key).toDoubleOrNull() ?: 0.0
        val sign = if (text("sign") in NEGATIVE_SIGNS) -1.0 else 1.0
        return OverseasStockRank(
            rank = 0, exchange = text("excd"), symbol = text("symb"), name = text("name"), ename = text("ename"),
            price = number("last"), diff = sign * number("diff"), rate = number("rate"), tradingValue = number("tamt"),
        )
    }

    companion object {
        private val NEGATIVE_SIGNS = setOf("4", "5")
    }
}
