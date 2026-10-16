package judozoo.platform.google

import io.micrometer.core.instrument.MeterRegistry
import judozoo.library.web.MeteredInterceptor
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

@Component
@EnableConfigurationProperties(GoogleProperties::class)
class GoogleOAuth(private val properties: GoogleProperties, private val clock: Clock, registry: MeterRegistry) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = JsonMapper.builder().build()

    data class Account(val sub: String, val email: String)

    private val rest = RestClient.builder()
        .requestFactory(
            JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build(),
            ).apply { setReadTimeout(Duration.ofSeconds(15)) },
        )
        .requestInterceptor(MeteredInterceptor("google", registry) { it.uri.path })
        .build()

    val configured: Boolean get() = properties.configured

    fun authorizeUrl(state: String, nonce: String): String = UriComponentsBuilder.fromUriString(properties.authorizeUrl)
        .queryParam("response_type", "code")
        .queryParam("client_id", properties.clientId)
        .queryParam("redirect_uri", properties.redirectUri)
        .queryParam("scope", "openid email")
        .queryParam("state", state)
        .queryParam("nonce", nonce)
        .encode().build().toUriString()

    fun exchange(code: String, nonce: String): Account? {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "authorization_code")
            add("code", code)
            add("client_id", properties.clientId)
            add("client_secret", properties.clientSecret)
            add("redirect_uri", properties.redirectUri)
        }
        val body = rest.post().uri(properties.tokenUrl).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(String::class.java)
        val idToken = json.readTree(body)["id_token"]?.asString() ?: return null.also { log.warning("토큰 응답에 id_token이 없다") }
        val claims = decode(idToken) ?: return null.also { log.warning("ID 토큰을 읽지 못했다") }
        return verified(claims, nonce)
    }

    private fun verified(claims: JsonNode, nonce: String): Account? {
        val iss = claims["iss"]?.asString()
        val aud = claims["aud"]?.let { if (it.isArray) it.values().map { a -> a.asString() } else listOf(it.asString()) }.orEmpty()
        val exp = claims["exp"]?.asLong()?.let(Instant::ofEpochSecond)
        when {
            iss !in ISSUERS -> log.warning("ID 토큰 발급자가 구글이 아니다 iss={}", iss)
            properties.clientId !in aud -> log.warning("ID 토큰이 이 앱 것이 아니다")
            exp == null || !clock.instant().isBefore(exp) -> log.warning("ID 토큰이 만료됐다")
            claims["nonce"]?.asString() != nonce -> log.warning("ID 토큰 nonce가 로그인 시작과 다르다")
            else -> {
                val sub = claims["sub"]?.asString()
                val email = claims["email"]?.asString()
                if (sub.isNullOrEmpty() || email.isNullOrEmpty()) {
                    log.warning("ID 토큰에 sub/email이 없다 — scope 설정을 확인해야 한다")
                    return null
                }
                return Account(sub, email)
            }
        }
        return null
    }

    private fun decode(idToken: String): JsonNode? = runCatching {
        json.readTree(Base64.getUrlDecoder().decode(idToken.split(".")[1]))
    }.getOrNull()

    private fun org.slf4j.Logger.warning(message: String, vararg args: Any?) = warn(message, *args)

    companion object {
        private val ISSUERS = setOf("https://accounts.google.com", "accounts.google.com")
    }
}
