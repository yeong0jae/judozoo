package judozoo.platform.kiwoom

import io.micrometer.core.instrument.MeterRegistry
import judozoo.library.brokertoken.IssuedToken.Issued
import judozoo.platform.TokenHttp
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.Duration

class KiwoomTokenIssuer(private val properties: KiwoomProperties, private val clock: Clock, registry: MeterRegistry) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val rest = TokenHttp.client(properties.baseUrl, "kiwoom", registry)

    fun issue(): Issued {
        val (status, location, body) = rest.post().uri("/oauth2/token")
            .contentType(MediaType.parseMediaType("application/json;charset=UTF-8"))
            .body(mapOf("grant_type" to "client_credentials", "appkey" to properties.appKey, "secretkey" to properties.appSecret))
            .exchange { _, response ->
                Triple(response.statusCode.value(), response.headers.getFirst("location"), response.bodyTo(String::class.java).orEmpty())
            }
        if (status != 200) {
            log.error("Kiwoom token 발급 실패 status={} location={} body={}", status, location ?: "-", body.take(200))
            throw IllegalStateException("Kiwoom token 발급 실패 status=$status")
        }
        val json: JsonNode = MAPPER.readTree(body)
        val code = json["return_code"]?.takeUnless { it.isNull }?.asInt()
        if (code != null && code != 0) {
            val msg = json["return_msg"]?.asString()
            log.error("Kiwoom 인증 실패 code={} msg={}", code, msg)
            throw IllegalStateException("Kiwoom API error: $msg")
        }
        val token = listOf("token", "access_token").firstNotNullOfOrNull { json[it]?.takeUnless { n -> n.isNull }?.asString()?.ifEmpty { null } }
            ?: throw IllegalStateException("Kiwoom token 응답에 토큰 없음")
        log.info("Kiwoom access token 발급 — expires_dt={}", json["expires_dt"]?.asString() ?: "N/A")
        return Issued(token, clock.instant().plus(LIFETIME))
    }

    companion object {
        private val LIFETIME = Duration.ofHours(23)
        private val MAPPER = tools.jackson.databind.json.JsonMapper.builder().build()
    }
}
