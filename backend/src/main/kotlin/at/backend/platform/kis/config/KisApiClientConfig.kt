package at.backend.platform.kis.config

import at.backend.platform.kis.KisAccessTokenProvider
import at.backend.platform.kis.KisApprovalKeyProvider
import at.backend.platform.kis.KisRateLimiter
import at.backend.platform.kis.client.KisAuthClient
import at.backend.platform.kis.client.KisRealQuotationClient
import at.backend.platform.kis.client.KisRestClient
import at.backend.platform.kis.client.KisWebSocketClient
import kotlinx.coroutines.CoroutineScope
import tools.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.socket.client.WebSocketClient
import org.springframework.web.socket.client.standard.StandardWebSocketClient
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
            trIdBuy = properties.tr.buy,
            trIdSell = properties.tr.sell,
            trIdCancel = properties.tr.cancel,
            trIdBalance = properties.tr.balance,
            marketDivCode = properties.marketDivCode,
            exchangeId = properties.exchangeId,
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
                    request.headers.setBearerAuth(tokenProvider.token)
                    request.headers.set("appkey", properties.appKey)
                    request.headers.set("appsecret", properties.appSecret)
                    request.headers.set("custtype", CUSTTYPE_INDIVIDUAL)
                    execution.execute(request, body)
                }
                .build(),
        )

    @Bean
    fun kisRealQuotationClient(
        properties: KisRealQuotationProperties,
        rateLimiter: KisRateLimiter,
    ): KisRealQuotationClient {
        val authClient = KisAuthClient(
            appKey = properties.appKey,
            appSecret = properties.appSecret,
            restClient = RestClient.builder()
                .baseUrl(properties.baseUrl)
                .requestFactory(SimpleClientHttpRequestFactory())
                .build(),
        )
        val tokenProvider = KisAccessTokenProvider(authClient)
        return KisRealQuotationClient(
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
                    request.headers.setBearerAuth(tokenProvider.token)
                    request.headers.set("appkey", properties.appKey)
                    request.headers.set("appsecret", properties.appSecret)
                    request.headers.set("custtype", CUSTTYPE_INDIVIDUAL)
                    execution.execute(request, body)
                }
                .build(),
        )
    }

    @Bean
    fun kisWebSocketClient(): WebSocketClient = StandardWebSocketClient()

    @Bean
    fun kisRealtimeClient(
        properties: KisProperties,
        approvalKeyProvider: KisApprovalKeyProvider,
        webSocketClient: WebSocketClient,
        objectMapper: ObjectMapper,
        applicationScope: CoroutineScope,
    ): KisWebSocketClient =
        KisWebSocketClient(
            properties = properties,
            approvalKeyProvider = approvalKeyProvider,
            webSocketClient = webSocketClient,
            objectMapper = objectMapper,
            applicationScope = applicationScope,
        )

    companion object {
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        private val READ_TIMEOUT: Duration = Duration.ofSeconds(3)
        private const val CUSTTYPE_INDIVIDUAL = "P"
    }
}
