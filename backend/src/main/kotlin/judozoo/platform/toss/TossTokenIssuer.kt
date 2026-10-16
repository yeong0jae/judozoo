package judozoo.platform.toss

import io.micrometer.core.instrument.MeterRegistry
import judozoo.library.brokertoken.IssuedToken.Issued
import judozoo.platform.TokenHttp
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import java.time.Clock
import java.time.Duration

class TossTokenIssuer(private val properties: TossProperties, private val clock: Clock, registry: MeterRegistry, restClient: RestClient.Builder = RestClient.builder()) {
    private val rest = TokenHttp.client(properties.baseUrl, "toss", registry, restClient)

    fun issue(): Issued {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "client_credentials")
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
        private const val EXPIRY_MARGIN_SECONDS = 300L
        private const val MIN_LIFETIME_SECONDS = 60L
        private val MAPPER = tools.jackson.databind.json.JsonMapper.builder().build()
    }
}
