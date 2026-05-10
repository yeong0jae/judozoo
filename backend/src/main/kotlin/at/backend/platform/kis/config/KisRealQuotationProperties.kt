package at.backend.platform.kis.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * VTS에서 미지원 quotation API(예: search-stock-info, CTPF1002R)를
 * 실거래 자격증명으로 호출하기 위한 별도 설정.
 * 활성 프로필과 무관하게 항상 REAL_KIS_* env에서 주입.
 */
@ConfigurationProperties(prefix = "kis.real-quotation")
data class KisRealQuotationProperties(
    val appKey: String,
    val appSecret: String,
    val baseUrl: String,
)
