package judozoo.platform.kis

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode

/** KIS 해외주식 거래대금순위 (HHDFS76320010) — 거래소별 당일 상위. */
@Component
class KisOverseasRanking(private val client: KisClient) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * KIS 응답 원형 그대로. 숫자 변환은 쓰는 쪽에서 한다.
     *
     * `sign`이 등락 방향을 준다(1:상한가 2:상승 3:보합 4:하한가 5:하락). `diff`/`rate`는 실측상 **부호가 붙어 온다**
     * (`+0.96`, `-26.87`). 다만 응답에 따라 다를 수 있으므로, 쓰는 쪽은 `sign`을 기준으로 삼고 값은 크기로 다룬다.
     */
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
        /** 달러 단위 */
        val tamt: String,
        val ename: String,
    )

    /** 캐시하지 않는다 — 세 거래소를 합친 풀 단위로 `overseasleadingstock`이 쥔다. 실패는 예외로 올린다. */
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
