package at.backend.platform.kis.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * VTS에서 미지원 quotation API(예: search-stock-info, CTPF1002R)를
 * 실거래 자격증명으로 호출하기 위한 별도 설정. Kiwoom 인스턴스에서는 사용되지 않으나
 * KisApiClientConfig가 항상-active라 binding은 일어남 → 빈 값 default.
 */
@ConfigurationProperties(prefix = "kis.real-quotation")
data class KisRealQuotationProperties(
    val appKey: String = "",
    val appSecret: String = "",
    val baseUrl: String = "",
)
