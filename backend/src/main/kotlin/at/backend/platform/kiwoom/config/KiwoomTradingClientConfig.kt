package at.backend.platform.kiwoom.config

import at.backend.platform.kiwoom.client.KiwoomAuthClient
import at.backend.platform.kiwoom.client.KiwoomTradingClient
import at.backend.platform.kiwoom.client.KiwoomWebSocketClient
import kotlinx.coroutines.CoroutineScope
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import org.springframework.web.socket.client.WebSocketClient
import tools.jackson.databind.ObjectMapper

/**
 * `kiwoom` profile에서만 활성화되는 트레이딩 빈 와이어링.
 * leadingstock용 시세 클라이언트(`KiwoomMarketClient` 등)는 항상-active이므로 여기서 다시 등록하지 않음.
 */
@Configuration
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
