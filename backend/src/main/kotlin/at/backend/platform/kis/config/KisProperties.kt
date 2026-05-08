package at.backend.platform.kis.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "kis")
data class KisProperties(
    val appKey: String,
    val appSecret: String,
    val accountNo: String,
    val accountProductCode: String,
    val baseUrl: String,
    val wsUrl: String,
    val rateLimitPerSecond: Int,
    val tr: Tr,
    val marketDivCode: String,    // FID_COND_MRKT_DIV_CODE — 시세 시장 구분 (real: UN 통합, vts: J KRX 단독)
    val exchangeId: String,        // EXCG_ID_DVSN_CD — 주문 거래소 라우팅 (real: SOR, vts: KRX)
) {
    data class Tr(
        val buy: String,
        val sell: String,
        val cancel: String,
        val balance: String,
        val executionNotice: String,
    )
}
