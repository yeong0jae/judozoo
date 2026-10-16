package judozoo.platform.toss

import io.micrometer.core.instrument.MeterRegistry
import judozoo.library.brokertoken.IssuedToken.Issued
import judozoo.platform.TokenHttp
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import java.time.Clock
import java.time.Duration

/**
 * 토스 접근토큰 발급(`/oauth2/token`, client credentials). client당 유효 토큰이 1개뿐이다.
 * 만료 5분 전에 갱신하도록 수명을 줄이되, 아주 짧게 와도 최소 1분은 쓴다.
 */
class TossTokenIssuer(private val properties: TossProperties, private val clock: Clock, registry: MeterRegistry) {
    private val rest = TokenHttp.client(properties.baseUrl, "toss", registry)

    fun issue(): Issued {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "client_credentials")
            // 시크릿 주입 시 끼어든 개행·공백을 흡수한다 — 남아 있으면 401 invalid_client로 거부된다
            add("client_id", properties.clientId.trim())
            add("client_secret", properties.clientSecret.trim())
        }
        val (status, body) = rest.post().uri("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
            .exchange { _, response -> response.statusCode.value() to response.bodyTo(String::class.java).orEmpty() }
        if (status != 200) throw IllegalStateException("Toss token 발급 실패 status=$status")
        val json = MAPPER.readTree(body)
        val token = json["access_token"]?.takeUnless { it.isNull }?.asString()?.ifEmpty { null } ?: throw IllegalStateException("Toss 토큰 응답이 비어있음")
        val lifetime = maxOf((json["expires_in"]?.asLong() ?: 86_400) - EXPIRY_MARGIN_SECONDS, MIN_LIFETIME_SECONDS)
        return Issued(token, clock.instant().plus(Duration.ofSeconds(lifetime)))
    }

    companion object {
        /** 만료 5분 전에 갱신 */
        private const val EXPIRY_MARGIN_SECONDS = 300L
        private const val MIN_LIFETIME_SECONDS = 60L
        private val MAPPER = tools.jackson.databind.json.JsonMapper.builder().build()
    }
}
