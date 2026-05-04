package at.backend.market.application

import at.backend.common.test.IntegrationTestBase
import at.backend.common.test.KisRestClientMockConfig
import at.backend.common.test.KisWebSocketClientMockConfig
import at.backend.platform.kis.client.KisRestClient
import at.backend.platform.kis.client.KisWebSocketClient
import at.backend.platform.kis.client.response.KisCurrentPriceResponse
import at.backend.market.domain.PriceTick
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import java.time.Instant

@Import(KisRestClientMockConfig::class, KisWebSocketClientMockConfig::class)
@TestPropertySource(properties = ["trading.market.poll-interval-millis=50"])
class MarketDataStreamTest(
    @Autowired private val marketDataStream: MarketDataStream,
    @Autowired private val webSocketClient: KisWebSocketClient,
    @Autowired private val restClient: KisRestClient,
) : IntegrationTestBase() {

    init {
        beforeEach {
            marketDataStream.reset()
            clearMocks(restClient, answers = false)
            clearMocks(webSocketClient, answers = false)
            every { restClient.getCurrentPrice(any()) } returns priceResponse(70_000)
        }

        context("WS 정상 모드") {
            test("WebSocket 통보 PriceTick이 그대로 전달된다") {
                val channels = KisWebSocketClientMockConfig.channelsOf(webSocketClient)
                channels.connectionState.tryEmit(true)
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

        context("WS 끊김 fallback") {
            test("WS 끊김 이벤트 수신 시 모드가 POLLING으로 전환된다") {
                val channels = KisWebSocketClientMockConfig.channelsOf(webSocketClient)
                marketDataStream.subscribe("005930")

                channels.connectionState.tryEmit(false)
                withTimeout(2000) {
                    while (marketDataStream.mode.first() != MarketDataStream.MarketMode.POLLING) {
                        delay(20)
                    }
                }
            }

            test("폴링 모드에서 REST 응답이 PriceTick으로 발행된다") {
                val channels = KisWebSocketClientMockConfig.channelsOf(webSocketClient)
                every { restClient.getCurrentPrice("005930") } returns priceResponse(71_000)
                marketDataStream.subscribe("005930")

                val received = coroutineScope {
                    val deferred = async {
                        withTimeout(2000) { marketDataStream.priceTicks.first() }
                    }
                    delay(100)
                    channels.connectionState.tryEmit(false)
                    deferred.await()
                }
                received.stockCode shouldBe "005930"
                received.price shouldBe 71_000
            }
        }

        context("WS 재연결") {
            test("재연결 시 모드가 WS로 복귀한다") {
                val channels = KisWebSocketClientMockConfig.channelsOf(webSocketClient)
                marketDataStream.subscribe("005930")

                channels.connectionState.tryEmit(false)
                withTimeout(2000) {
                    while (marketDataStream.mode.first() != MarketDataStream.MarketMode.POLLING) {
                        delay(20)
                    }
                }

                channels.connectionState.tryEmit(true)
                withTimeout(2000) {
                    while (marketDataStream.mode.first() != MarketDataStream.MarketMode.WS) {
                        delay(20)
                    }
                }
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

    private fun priceResponse(price: Int) =
        KisCurrentPriceResponse(output = KisCurrentPriceResponse.Output(stckPrpr = price.toString()))

    private fun tick(code: String, price: Int) =
        PriceTick(stockCode = code, price = price, timestamp = Instant.now())
}
