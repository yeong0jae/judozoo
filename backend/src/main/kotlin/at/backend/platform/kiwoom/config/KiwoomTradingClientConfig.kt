package at.backend.platform.kiwoom.config

import at.backend.platform.kiwoom.client.KiwoomAuthClient
import at.backend.platform.kiwoom.client.KiwoomExecutionWebSocketClient
import at.backend.platform.kiwoom.client.KiwoomTradingClient
import kotlinx.coroutines.CoroutineScope
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.web.client.RestClient
import org.springframework.web.socket.client.WebSocketClient
import tools.jackson.databind.ObjectMapper

/**
 * `kiwoom` profile에서만 활성화되는 트레이딩 빈 와이어링.
 * leadingstock용 시세 클라이언트(`KiwoomMarketClient` 등)는 항상-active이므로 여기서 다시 등록하지 않음.
 */
@Configuration
@Profile("kiwoom")
class KiwoomTradingClientConfig {

    @Bean
    fun kiwoomTradingClient(
        kiwoomRestClient: RestClient,
        authClient: KiwoomAuthClient,
        tradingProperties: KiwoomTradingProperties,
    ): KiwoomTradingClient =
        KiwoomTradingClient(
            restClient = kiwoomRestClient,
            authClient = authClient,
            dmstStexTp = tradingProperties.dmstStexTp,
        )

    @Bean
    fun kiwoomExecutionWebSocketClient(
        tradingProperties: KiwoomTradingProperties,
        authClient: KiwoomAuthClient,
        webSocketClient: WebSocketClient,             // KisApiClientConfig가 만든 일반 WS 클라이언트 재사용 (버퍼 1MB)
        objectMapper: ObjectMapper,
        applicationScope: CoroutineScope,
    ): KiwoomExecutionWebSocketClient =
        KiwoomExecutionWebSocketClient(
            wsUrl = tradingProperties.wsUrl,
            authClient = authClient,
            webSocketClient = webSocketClient,
            objectMapper = objectMapper,
            applicationScope = applicationScope,
        )
}
