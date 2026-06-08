package at.backend.market.application

import at.backend.common.test.IntegrationTestBase
import at.backend.common.test.KisWebSocketClientMockConfig
import at.backend.market.domain.PriceTick
import at.backend.platform.kis.client.KisWebSocketClient
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant

class PriceTickDataStreamTest(
    @Autowired private val priceTickDataStream: PriceTickDataStream,
    @Autowired private val webSocketClient: KisWebSocketClient,
) : IntegrationTestBase() {

    init {
        beforeEach {
            clearMocks(webSocketClient, answers = false)
        }

        context("WS 가격 통보 전달") {
            test("WebSocket으로 들어온 PriceTick이 그대로 컨슈머에게 전달된다") {
                val channels = KisWebSocketClientMockConfig.channelsOf(webSocketClient)
                priceTickDataStream.subscribe("005930")

                val received = coroutineScope {
                    val deferred = async {
                        withTimeout(2000) { priceTickDataStream.priceTicks.first() }
                    }
                    delay(100)
                    channels.priceTicks.tryEmit(tick("005930", 70_000))
                    deferred.await()
                }
                received.stockCode shouldBe "005930"
                received.price shouldBe 70_000
            }
        }

        context("KIS WS 위임") {
            test("subscribe는 KIS WS subscribePrice를 호출한다") {
                priceTickDataStream.subscribe("005930")
                verify(exactly = 1) { webSocketClient.subscribePrice("005930") }
            }

            test("unsubscribe는 KIS WS unsubscribePrice를 호출한다") {
                priceTickDataStream.unsubscribe("005930")
                verify(exactly = 1) { webSocketClient.unsubscribePrice("005930") }
            }
        }
    }

    private fun tick(code: String, price: Int) =
        PriceTick(stockCode = code, price = price, timestamp = Instant.now())
}
