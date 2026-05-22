package at.backend.platform.kiwoom.client

import at.backend.platform.kiwoom.config.KiwoomApiProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class KiwoomThemeClient(
    private val kiwoomRestClient: RestClient,
    private val properties: KiwoomApiProperties,
    private val authClient: KiwoomAuthClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun fetchThemeRankForStock(stockCode: String): Int? {
        try {
            val token = authClient.getAccessToken()
            log.info("Fetching theme rank for stock {}", stockCode)

            val response = kiwoomRestClient.get()
                .uri("/v1/stock/{stockCode}/theme", stockCode)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .retrieve()
                .body(ThemeRankResponse::class.java)
                ?: return null

            return response.rank
        } catch (e: Exception) {
            log.error("Failed to fetch theme rank for {}", stockCode, e)
            return null
        }
    }

    data class ThemeRankResponse(
        val rank: Int,
        val themeName: String? = null,
    )
}
