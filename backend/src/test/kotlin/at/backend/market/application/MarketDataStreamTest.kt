package at.backend.market.application

import at.backend.common.test.IntegrationTestBase
import at.backend.common.test.KisRestClientMockConfig
import at.backend.common.test.KisWebSocketClientMockConfig
import at.backend.market.domain.PriceTick
import at.backend.platform.kis.client.KisWebSocketClient
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.time.Instant

@Import(KisRestClientMockConfig::class, KisWebSocketClientMockConfig::class)
class MarketDataStreamTest(
    @Autowired private val marketDataStream: MarketDataStream,
    @Autowired private val webSocketClient: KisWebSocketClient,
) : IntegrationTestBase() {

    init {
        beforeEach {
            marketDataStream.reset()
            clearMocks(webSocketClient, answers = false)
        }

        context("WS 가격 통보 전달") {
            test("WebSocket으로 들어온 PriceTick이 그대로 컨슈머에게 전달된다") {
                val channels = KisWebSocketClientMockConfig.channelsOf(webSocketClient)
                marketDataStream.subscribe("005930")

                val received = coroutineScope {
                    val deferred = async {
                        withTimeout(2000) { marketDataStream.priceTicks.first() }
                    }
                    delay(100)
                    channels.priceTicks.tryEmit(tick("005930", 70_000))
                    deferred.await()
                }
                received.stockCode shouldBe "005930"
                received.price shouldBe 70_000
            }
        }

        context("다중 종목 구독") {
            test("같은 종목을 두 번 구독해도 KIS WS subscribe는 한 번만 호출된다") {
                marketDataStream.subscribe("005930")
                marketDataStream.subscribe("005930")

                verify(exactly = 1) { webSocketClient.subscribePrice("005930") }
            }

            test("참조 카운트가 0이 될 때만 KIS WS unsubscribe가 호출된다") {
                marketDataStream.subscribe("005930")
                marketDataStream.subscribe("005930")
                marketDataStream.unsubscribe("005930")
                verify(exactly = 0) { webSocketClient.unsubscribePrice("005930") }

                marketDataStream.unsubscribe("005930")
                verify(exactly = 1) { webSocketClient.unsubscribePrice("005930") }
            }

            test("여러 종목이 활성 상태에 함께 노출된다") {
                marketDataStream.subscribe("005930")
                marketDataStream.subscribe("035420")
                marketDataStream.activeStockCodes() shouldContain "005930"
                marketDataStream.activeStockCodes() shouldContain "035420"
                marketDataStream.activeStockCodes() shouldHaveSize 2
            }
        }
    }

    private fun tick(code: String, price: Int) =
        PriceTick(stockCode = code, price = price, timestamp = Instant.now())
}
