package at.backend.platform.kis.config

import at.backend.platform.kis.KisAccessTokenProvider
import at.backend.platform.kis.KisApprovalKeyProvider
import at.backend.platform.kis.KisRateLimiter
import at.backend.platform.kis.client.KisAuthClient
import at.backend.platform.kis.client.KisRealQuotationClient
import at.backend.platform.kis.client.KisRestClient
import at.backend.platform.kis.client.KisWebSocketClient
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import tools.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.socket.client.WebSocketClient
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import java.time.Duration

@Configuration
class KisApiClientConfig {

    private val log = KotlinLogging.logger {}

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
                .requestInterceptor(kisInterceptor(rateLimiter, tokenProvider, properties.appKey, properties.appSecret))
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
                .requestInterceptor(kisInterceptor(rateLimiter, tokenProvider, properties.appKey, properties.appSecret))
                .build(),
        )
    }

    /** 모든 KIS REST 호출의 공통 진입점 — rate limit 획득 + 인증 헤더 + 호출 직전/직후 로깅. */
    private fun kisInterceptor(
        rateLimiter: KisRateLimiter,
        tokenProvider: KisAccessTokenProvider,
        appKey: String,
        appSecret: String,
    ) = ClientHttpRequestInterceptor { request, body, execution ->
        rateLimiter.acquire()
        request.headers.setBearerAuth(tokenProvider.token)
        request.headers.set("appkey", appKey)
        request.headers.set("appsecret", appSecret)
        request.headers.set("custtype", CUSTTYPE_INDIVIDUAL)

        val trId = request.headers.getFirst("tr_id")
        log.info { "KIS 호출 → ${request.method} ${request.uri.path} trId=$trId" }
        val startedAtNanos = System.nanoTime()
        try {
            val response = execution.execute(request, body)
            val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000
            log.info { "KIS 응답 ← ${request.method} ${request.uri.path} trId=$trId status=${response.statusCode} ${elapsedMs}ms" }
            response
        } catch (e: Exception) {
            val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000
            log.warn(e) { "KIS 호출 실패 ✗ ${request.method} ${request.uri.path} trId=$trId ${elapsedMs}ms" }
            throw e
        }
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
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(15)
        private val READ_TIMEOUT: Duration = Duration.ofSeconds(10)
        private const val CUSTTYPE_INDIVIDUAL = "P"
    }
}
