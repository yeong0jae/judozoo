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
            // KisApiClientConfig의 글로벌 인터셉터와 동일하게 custtype을 박아 prod 요청 형태와 일치시킨다.
            .requestInterceptor { request, body, execution ->
                request.headers.set("custtype", "P")
                execution.execute(request, body)
            }

    fun restClient(wireMock: WireMockServer): RestClient =
        builder(wireMock).build()
}
