package at.backend.common.test

import at.backend.platform.kis.client.KisWebSocketClient
import at.backend.trading.domain.order.ExecutionNotice
import at.backend.market.domain.PriceTick
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

@TestConfiguration
class KisWebSocketClientMockConfig {

    @Bean
    @Primary
    fun mockKisWebSocketClient(): KisWebSocketClient {
        val priceTicks = MutableSharedFlow<PriceTick>(extraBufferCapacity = 1024)
        val executionNotices = MutableSharedFlow<ExecutionNotice>(extraBufferCapacity = 256)
        val connectionState = MutableSharedFlow<Boolean>(replay = 1, extraBufferCapacity = 16)
        return mockk<KisWebSocketClient>(relaxed = true).also { client ->
            every { client.priceTicks } returns priceTicks.asSharedFlow()
            every { client.executionNotices } returns executionNotices.asSharedFlow()
            every { client.connectionState } returns connectionState.asSharedFlow()
            every { client.subscribePrice(any()) } returns Unit
            every { client.unsubscribePrice(any()) } returns Unit
            every { client.subscribeExecutionNotice() } returns Unit
            wsTestChannels[client] = WsTestChannels(priceTicks, executionNotices, connectionState)
        }
    }

    companion object {
        private val wsTestChannels = mutableMapOf<KisWebSocketClient, WsTestChannels>()

        fun channelsOf(client: KisWebSocketClient): WsTestChannels =
            wsTestChannels[client] ?: error("Mock 채널을 찾을 수 없습니다")
    }

    data class WsTestChannels(
        val priceTicks: MutableSharedFlow<PriceTick>,
        val executionNotices: MutableSharedFlow<ExecutionNotice>,
        val connectionState: MutableSharedFlow<Boolean>,
    )
}
