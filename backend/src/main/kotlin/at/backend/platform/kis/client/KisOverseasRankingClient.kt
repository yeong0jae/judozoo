package at.backend.platform.kis.client

import at.backend.platform.kis.config.KisApiProperties
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class KisOverseasRankingClient(
    private val kisRestClient: RestClient,
    private val authClient: KisAuthClient,
    private val properties: KisApiProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 해외주식 거래대금순위 (HHDFS76320010) — 거래소별 당일 상위 30위 */
    @Cacheable("kisOverseasRanking", key = "#excd")
    fun fetchTradingValueRanking(excd: String): List<OverseasRankItem> {
        val token = authClient.getAccessToken()
        log.info("KIS 해외주식 거래대금순위 조회: excd={}", excd)

        val response = kisRestClient.get()
            .uri { builder ->
                builder.path("/uapi/overseas-stock/v1/ranking/trade-pbmn")
                    .queryParam("KEYB", "")
                    .queryParam("AUTH", "")
                    .queryParam("EXCD", excd)
                    .queryParam("NDAY", "0")
                    .queryParam("VOL_RANG", "0")
                    .queryParam("PRC1", "")
                    .queryParam("PRC2", "")
                    .build()
            }
            .header("content-type", "application/json; charset=utf-8")
            .header("authorization", "Bearer $token")
            .header("appkey", properties.appKey)
            .header("appsecret", properties.appSecret)
            .header("tr_id", "HHDFS76320010")
            .header("custtype", "P")
            .retrieve()
            .body(RankingResponse::class.java)
            ?: throw IllegalStateException("KIS 거래대금순위 응답이 null (excd=$excd)")

        if (response.rt_cd != "0") {
            throw IllegalStateException("KIS 거래대금순위 오류: ${response.msg1} (excd=$excd)")
        }

        return response.output2 ?: emptyList()
    }

    data class RankingResponse(
        val rt_cd: String,
        val msg_cd: String,
        val msg1: String,
        val output2: List<OverseasRankItem>?,
    )

    data class OverseasRankItem(
        val rank: String,
        val excd: String,
        val symb: String,
        val name: String,
        val last: String,
        val sign: String,  // 1:상한가 2:상승 3:보합 4:하한가 5:하락
        val diff: String,  // 절댓값 — sign으로 방향 결정
        val rate: String,  // 절댓값 — sign으로 방향 결정
        val tvol: String,
        val tamt: String,  // 달러 단위
        val ename: String,
    )
}
