package at.backend.common.test

import com.github.tomakehurst.wiremock.WireMockServer
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

object TestRestClientConfig {

    private val TIMEOUT: Duration = Duration.ofSeconds(1)

    fun builder(wireMock: WireMockServer): RestClient.Builder =
        RestClient.builder()
            .baseUrl("http://localhost:${wireMock.port()}")
            .requestFactory(
                SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(TIMEOUT)
                    setReadTimeout(TIMEOUT)
                }
            )

    fun restClient(wireMock: WireMockServer): RestClient =
        builder(wireMock).build()
}
