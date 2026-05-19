package at.backend.stock.infrastructure

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 종목 마스터 파일(.mst) 다운로드 설정. KIS 공식 샘플이 쓰는 평문 CDN으로,
 * OpenAPI 토큰/레이트리밋과 무관하며 vts/real 프로필 영향이 없다.
 * URL이 바뀔 수 있으므로 설정값으로 둔다.
 */
@ConfigurationProperties(prefix = "stock.master")
data class StockMasterProperties(
    val kospiUrl: String,
    val kosdaqUrl: String,
)
