package at.backend.platform.kis.config

import at.backend.platform.kis.KisAccessTokenProvider
import at.backend.platform.kis.KisRateLimiter
import at.backend.platform.kis.client.KisAuthClient
import at.backend.platform.kis.client.KisRestClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

@Configuration
class KisApiClientConfig {

    @Bean
    fun kisAuthClient(properties: KisProperties): KisAuthClient =
        KisAuthClient(
            appKey = properties.appKey,
            appSecret = properties.appSecret,
            restClient = RestClient.builder()
                .baseUrl(properties.baseUrl)
                .requestFactory(SimpleClientHttpRequestFactory())
                .build(),
        )

    @Bean
    fun kisRestClient(
        properties: KisProperties,
        tokenProvider: KisAccessTokenProvider,
        rateLimiter: KisRateLimiter,
    ): KisRestClient =
        KisRestClient(
            accountNo = properties.accountNo,
            accountProductCode = properties.accountProductCode,
            restClient = RestClient.builder()
                .baseUrl(properties.baseUrl)
                .requestFactory(
                    SimpleClientHttpRequestFactory().apply {
                        setConnectTimeout(CONNECT_TIMEOUT)
                        setReadTimeout(READ_TIMEOUT)
                    }
                )
                .requestInterceptor { request, body, execution ->
                    rateLimiter.acquire()
                    request.headers.setBearerAuth(tokenProvider.getToken())
                    request.headers.set("appkey", properties.appKey)
                    request.headers.set("appsecret", properties.appSecret)
                    execution.execute(request, body)
                }
                .build(),
        )

    companion object {
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        private val READ_TIMEOUT: Duration = Duration.ofSeconds(3)
    }
}
