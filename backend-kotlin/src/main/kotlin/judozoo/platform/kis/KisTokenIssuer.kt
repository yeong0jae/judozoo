package judozoo.platform.kis

import io.micrometer.core.instrument.MeterRegistry
import judozoo.library.brokertoken.IssuedToken.Issued
import judozoo.platform.TokenHttp
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import java.time.Clock
import java.time.Duration

/**
 * KIS 접근토큰 발급(`/oauth2/tokenP`). 23시간 쓴다. **같은 앱키로 1분 안에 재발급하면 거부한다** —
 * 부르는 쪽([judozoo.library.brokertoken.IssuedToken])이 DB를 먼저 보고, 실패하면 1분 쉰다.
 */
class KisTokenIssuer(private val properties: KisProperties, private val clock: Clock, registry: MeterRegistry) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val rest = TokenHttp.client(properties.baseUrl, "kis", registry)

    fun issue(): Issued {
        val (status, body) = rest.post().uri("/oauth2/tokenP")
            .contentType(MediaType.parseMediaType("application/json; charset=utf-8"))
            .body(mapOf("grant_type" to "client_credentials", "appkey" to properties.appKey, "appsecret" to properties.appSecret))
            .exchange { _, response -> response.statusCode.value() to response.bodyTo(String::class.java).orEmpty() }
        if (status != 200) {
            // 본문에 사유 코드가 들어 있으므로 버리지 말고 남긴다
            log.error("KIS token 발급 실패 status={} body={}", status, body)
            throw IllegalStateException("KIS token 발급 실패 status=$status")
        }
        val token = MAPPER.readTree(body)["access_token"]?.takeUnless { it.isNull }?.asString()?.ifEmpty { null }
            ?: throw IllegalStateException("KIS token 응답에 access_token 없음")
        return Issued(token, clock.instant().plus(LIFETIME))
    }

    companion object {
        private val LIFETIME = Duration.ofHours(23)
        private val MAPPER = tools.jackson.databind.json.JsonMapper.builder().build()
    }
}
