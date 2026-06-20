package at.backend.platform.kiwoom.config

import at.backend.platform.kiwoom.client.KiwoomAuthClient
import at.backend.platform.kiwoom.client.KiwoomTradingClient
import at.backend.platform.kiwoom.client.KiwoomWebSocketClient
import jakarta.websocket.ContainerProvider
import kotlinx.coroutines.CoroutineScope
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import org.springframework.web.socket.client.WebSocketClient
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import tools.jackson.databind.ObjectMapper

/**
 * `kiwoom` profile에서만 활성화되는 트레이딩 빈 와이어링.
 * leadingstock용 시세 클라이언트(`KiwoomMarketClient` 등)는 항상-active이므로 여기서 다시 등록하지 않음.
 */
@Configuration
class KiwoomTradingClientConfig {

    /**
     * JSR-356 기본 텍스트 버퍼(8KB)로는 체결통보 등 큰 메시지에서 1009로 끊겨 무한 재연결에 빠진다 → 1MB로 확장.
     */
    @Bean
    fun webSocketClient(): WebSocketClient {
        val container = ContainerProvider.getWebSocketContainer().apply {
            defaultMaxTextMessageBufferSize = 1024 * 1024
            defaultMaxBinaryMessageBufferSize = 1024 * 1024
        }
        return StandardWebSocketClient(container)
    }

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

    /**
     * 주문체결(type=00) + 실시간 시세(type=0B)를 하나의 WS 연결에서 처리한다.
     * mockapi.kiwoom.com이 토큰당 WS 1개만 허용하기 때문에 두 connection을 띄우면 핑퐁 재연결 발생.
     */
    @Bean
    fun kiwoomWebSocketClient(
        tradingProperties: KiwoomTradingProperties,
        authClient: KiwoomAuthClient,
        webSocketClient: WebSocketClient,
        objectMapper: ObjectMapper,
        applicationScope: CoroutineScope,
    ): KiwoomWebSocketClient =
        KiwoomWebSocketClient(
            wsUrl = tradingProperties.wsUrl,
            authClient = authClient,
            webSocketClient = webSocketClient,
            objectMapper = objectMapper,
            applicationScope = applicationScope,
        )
}
