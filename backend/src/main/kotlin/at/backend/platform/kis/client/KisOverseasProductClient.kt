package at.backend.platform.kis.client

import at.backend.platform.kis.config.KisApiProperties
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class KisOverseasProductClient(
    private val kisRestClient: RestClient,
    private val authClient: KisAuthClient,
    private val properties: KisApiProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 상품기본정보(CTPF1702R)로 시가총액(달러) = 상장주식수 × 현재가. 데이터 없으면 null. */
    @Cacheable("kisOverseasMarketCap", key = "#excd + ':' + #symb", unless = "#result == null")
    fun fetchMarketCap(excd: String, symb: String): Long? {
        val token = authClient.getAccessToken()
        val response = kisRestClient.get()
            .uri { b ->
                b.path("/uapi/overseas-price/v1/quotations/search-info")
                    .queryParam("PRDT_TYPE_CD", prdtTypeCd(excd))
                    .queryParam("PDNO", symb)
                    .build()
            }
            .headers {
                it.set("content-type", "application/json; charset=utf-8")
                it.set("authorization", "Bearer $token")
                it.set("appkey", properties.appKey)
                it.set("appsecret", properties.appSecret)
                it.set("tr_id", "CTPF1702R")
                it.set("custtype", "P")
            }
            .retrieve()
            .body(ProductResponse::class.java)
            ?: return null

        if (response.rt_cd != "0") {
            log.warn("KIS 상품기본정보 오류: {} ({}:{})", response.msg1, excd, symb)
            return null
        }
        val o = response.output ?: return null
        val shares = o.lstg_stck_num.trim().toLongOrNull() ?: return null
        val price = o.ovrs_now_pric1.trim().toDoubleOrNull() ?: return null
        if (shares <= 0 || price <= 0) return null
        return (shares * price).toLong()
    }

    private fun prdtTypeCd(excd: String): String = when (excd) {
        "NAS" -> "512"
        "NYS" -> "513"
        "AMS" -> "529"
        else -> throw IllegalArgumentException("지원하지 않는 거래소: $excd")
    }

    private data class ProductResponse(
        val rt_cd: String,
        val msg1: String,
        val output: ProductOutput?,
    )

    private data class ProductOutput(
        val lstg_stck_num: String, // 상장주식수
        val ovrs_now_pric1: String, // 해외현재가격
    )
}
