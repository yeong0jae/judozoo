package at.backend.platform.kis.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * KIS 자격증명·엔드포인트·TR ID. Kiwoom-only 인스턴스(kiwoom-vts/kiwoom-real)에서는
 * kis: 섹션이 yaml에 없으므로 모두 기본값(빈 문자열/기본 rate)으로 binding된다.
 * 해당 인스턴스의 KisBrokerAdapter는 @Profile("kis") 게이팅으로 비활성, 빈 값이 실제 호출되지 않음.
 */
@ConfigurationProperties(prefix = "kis")
data class KisProperties(
    val appKey: String = "",
    val appSecret: String = "",
    val accountNo: String = "",
    val accountProductCode: String = "",
    val htsId: String = "",             // HTS 사용자 ID — 체결통보 WS 구독의 tr_key
    val baseUrl: String = "",
    val wsUrl: String = "",
    val rateLimitPerSecond: Int = 20,
    val tr: Tr = Tr(),
    val marketDivCode: String = "",     // FID_COND_MRKT_DIV_CODE — 시세 시장 구분 (real: UN 통합, vts: J KRX 단독)
    val exchangeId: String = "",        // EXCG_ID_DVSN_CD — 주문 거래소 라우팅 (real: SOR, vts: KRX)
) {
    data class Tr(
        val buy: String = "",
        val sell: String = "",
        val cancel: String = "",
        val balance: String = "",
        val executionNotice: String = "",
    )
}
