package judozoo.platform.kis

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode

@Component
class KisOverseasRanking(private val client: KisClient) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class OverseasRankItem(
        val rank: String,
        val excd: String,
        val symb: String,
        val name: String,
        val last: String,
        val sign: String,
        val diff: String,
        val rate: String,
        val tvol: String,
        val tamt: String,
        val ename: String,
    )

    fun fetchTradingValueRanking(excd: String): List<OverseasRankItem> {
        log.info("KIS 해외주식 거래대금순위 조회: excd={}", excd)
        val response = client.get(
            "/uapi/overseas-stock/v1/ranking/trade-pbmn", "HHDFS76320010",
            mapOf("KEYB" to "", "AUTH" to "", "EXCD" to excd, "NDAY" to "0", "VOL_RANG" to "0", "PRC1" to "", "PRC2" to ""),
        )
        if (response.status !in 200..299) throw IllegalStateException("KIS 거래대금순위 HTTP ${response.status} (excd=$excd)")
        if (!response.ok) throw IllegalStateException("KIS 거래대금순위 오류: ${response.msg1} (excd=$excd)")
        return response.body!!["output2"]?.values().orEmpty().map(::toItem)
    }

    private fun toItem(row: JsonNode): OverseasRankItem {
        fun s(key: String) = row[key]?.takeUnless { it.isNull }?.asString().orEmpty()
        return OverseasRankItem(
            rank = s("rank"), excd = s("excd"), symb = s("symb"), name = s("name"), last = s("last"), sign = s("sign"),
            diff = s("diff"), rate = s("rate"), tvol = s("tvol"), tamt = s("tamt"), ename = s("ename"),
        )
    }
}
