package at.backend.platform.kiwoom.client

import at.backend.platform.kiwoom.config.KiwoomApiProperties
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class KiwoomThemeClient(
    private val kiwoomRestClient: RestClient,
    @Suppress("unused") private val properties: KiwoomApiProperties,
    private val authClient: KiwoomAuthClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 종목이 속한 테마명 목록 (ka90001, 종목검색=qry_tp 2).
     * flu_pl_amt_tp=3(상위등락률)로 정렬해 그날 강한 테마가 앞에 온다. 실패 시 빈 리스트.
     *
     * 종목별 12시간 캐시(stockThemes) — 후보 목록·테마 캘린더 캡처가 공유한다.
     * 키는 6자리 단축코드로 정규화해 `_AL` 등 접미사가 달라도 같은 종목이면 캐시를 맞힌다.
     */
    // unless: 빈 결과(레이트리밋·일시 오류 포함)는 캐싱하지 않아 다음 조회에서 재시도되게 한다.
    @Cacheable("stockThemes", key = "#stockCode.substring(0, 6)", unless = "#result.isEmpty()")
    fun fetchThemesForStock(stockCode: String): List<String> {
        val shortCode = stockCode.substringBefore("_").take(6)
        try {
            val token = authClient.getAccessToken()
            val response = kiwoomRestClient.post()
                .uri("/api/dostk/thme")
                .header("authorization", "Bearer $token")
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("api-id", "ka90001")
                .body(
                    mapOf(
                        "qry_tp" to "2",        // 0:전체, 1:테마, 2:종목검색
                        "stk_cd" to shortCode,
                        "date_tp" to "1",        // 등락 기준 n일전
                        "thema_nm" to "",
                        "flu_pl_amt_tp" to "3",  // 3:상위등락률
                        "stex_tp" to "1",        // 1:KRX
                    ),
                )
                .retrieve()
                .body(ThemeGroupResponse::class.java)
                ?: return emptyList()

            if (response.return_code != null && response.return_code != 0) {
                log.error(
                    "Kiwoom theme error stk={}. code={}, msg={}",
                    shortCode, response.return_code, response.return_msg,
                )
                return emptyList()
            }
            return response.thema_grp.orEmpty()
                .mapNotNull { it.thema_nm?.takeIf(String::isNotBlank) }
        } catch (e: Exception) {
            log.error("Failed to fetch themes for {}", shortCode, e)
            return emptyList()
        }
    }

    data class ThemeGroupResponse(
        val thema_grp: List<ThemeGroup>? = null,
        val return_code: Int? = null,
        val return_msg: String? = null,
    )

    data class ThemeGroup(
        val thema_grp_cd: String? = null,
        val thema_nm: String? = null,
        val flu_rt: String? = null,
    )
}
