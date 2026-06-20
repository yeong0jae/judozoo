package at.backend.common.test

import at.backend.market.domain.PriceTick
import at.backend.platform.kiwoom.client.KiwoomWebSocketClient
import at.backend.trading.domain.order.ExecutionNotice
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/** 테스트에서 시세 틱·체결 통보를 직접 흘려보내기 위한 채널 묶음. */
class WsTestChannels(
    val priceTicks: MutableSharedFlow<PriceTick>,
    val executionNotices: MutableSharedFlow<ExecutionNotice>,
)

@TestConfiguration
class KiwoomWebSocketClientMockConfig {

    @Bean
    @Primary
    fun mockKiwoomWebSocketClient(): KiwoomWebSocketClient {
        val priceTicks = MutableSharedFlow<PriceTick>(extraBufferCapacity = 1024)
        val executionNotices = MutableSharedFlow<ExecutionNotice>(extraBufferCapacity = 256)
        return mockk<KiwoomWebSocketClient>(relaxed = true).also { client ->
            every { client.priceTicks } returns priceTicks.asSharedFlow()
            every { client.executionNotices } returns executionNotices.asSharedFlow()
            every { client.subscribePrice(any()) } returns Unit
            every { client.unsubscribePrice(any()) } returns Unit
            every { client.subscribeExecution() } returns Unit
            wsTestChannels[client] = WsTestChannels(priceTicks, executionNotices)
        }
    }

    companion object {
        private val wsTestChannels = mutableMapOf<KiwoomWebSocketClient, WsTestChannels>()

        fun channelsOf(client: KiwoomWebSocketClient): WsTestChannels =
            wsTestChannels[client] ?: error("Mock 채널을 찾을 수 없습니다")
    }
}
