package at.backend.platform.kis

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * appKey 단위 KIS 액세스 토큰 캐시. 동일 appKey로 발급된 토큰을 여러 KisAuthClient 인스턴스가
 * 공유하여 EGW00133(1분당 1회 토큰 발급) 위반 회피.
 *
 * - 메인 KisRestClient와 KisRealQuotationClient가 같은 appKey(예: kis-real 인스턴스의 REAL_KIS_APP_KEY)를
 *   쓰면 토큰 1회만 발급 → 메인·real-quotation이 공유.
 * - 다른 appKey(예: kis-vts 인스턴스: 메인 VTS_KIS_APP_KEY, real-quotation REAL_KIS_APP_KEY)는
 *   각자 별도 캐시 슬롯 → 독립적으로 발급.
 *
 * TTL은 KIS 토큰 유효기간 24h에 1h 마진을 둔 23h.
 */
@Component
class KisTokenRegistry {
    private val log = KotlinLogging.logger {}
    private val cache = ConcurrentHashMap<String, Cached>()

    fun getOrIssue(appKey: String, issuer: () -> String): String {
        cache[appKey]?.takeIf { Instant.now().isBefore(it.expiresAt) }?.let { return it.token }
        synchronized(cache) {
            cache[appKey]?.takeIf { Instant.now().isBefore(it.expiresAt) }?.let { return it.token }
            log.info { "KIS 토큰 신규 발급 appKey=${appKey.take(8)}***" }
            val token = issuer()
            cache[appKey] = Cached(token, Instant.now().plusSeconds(TTL_SECONDS))
            return token
        }
    }

    private data class Cached(val token: String, val expiresAt: Instant)

    companion object {
        private const val TTL_SECONDS: Long = 23 * 3600
    }
}
