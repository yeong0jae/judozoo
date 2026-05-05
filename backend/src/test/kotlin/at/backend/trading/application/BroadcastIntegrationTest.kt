package at.backend.trading.application

import at.backend.common.test.FixedTimeProviderConfig
import at.backend.common.test.KisRestClientMockConfig
import at.backend.common.test.KisWebSocketClientMockConfig
import at.backend.common.test.MutableTimeProvider
import at.backend.platform.kis.client.KisRestClient
import at.backend.platform.kis.client.response.KisBalanceResponse
import at.backend.platform.kis.client.response.KisCurrentPriceResponse
import at.backend.platform.kis.client.response.KisHolidayResponse
import at.backend.platform.kis.client.response.KisOrderResponse
import at.backend.platform.kis.client.response.KisStockSearchResponse
import at.backend.trading.domain.TradingInput
import at.backend.trading.infrastructure.repository.TradingCycleJpaRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.mockk.every
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.messaging.converter.MappingJackson2MessageConverter
import org.springframework.messaging.simp.stomp.StompFrameHandler
import org.springframework.messaging.simp.stomp.StompHeaders
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.messaging.WebSocketStompClient
import org.testcontainers.containers.MySQLContainer
import java.lang.reflect.Type
import java.math.BigDecimal
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(KisRestClientMockConfig::class, KisWebSocketClientMockConfig::class, FixedTimeProviderConfig::class)
class BroadcastIntegrationTest(
    @Autowired private val tradingService: TradingService,
    @Autowired private val cycleRepository: TradingCycleJpaRepository,
    @Autowired private val kisRestClient: KisRestClient,
    @Autowired private val timeProvider: MutableTimeProvider,
    @LocalServerPort private val port: Int,
) : FunSpec() {

    override fun extensions() = listOf(SpringExtension)

    companion object {
        private val mysql = MySQLContainer("mysql:8.4").also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl)
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
        }
    }

    private fun stubKisDefaults() {
        every { kisRestClient.searchStock(any()) } returns KisStockSearchResponse(
            output = listOf(KisStockSearchResponse.Output(pdno = "005930", prdtAbrvName = "삼성전자"))
        )
        every { kisRestClient.getCurrentPrice(any()) } returns KisCurrentPriceResponse(
            output = KisCurrentPriceResponse.Output(stckPrpr = "70000")
        )
        every { kisRestClient.getBalance() } returns KisBalanceResponse(
            output2 = listOf(KisBalanceResponse.Output(prvsRcdlExccAmt = "100000000"))
        )
        every { kisRestClient.checkHoliday(any()) } returns KisHolidayResponse(
            output = listOf(KisHolidayResponse.Output(bzdyYn = "Y"))
        )
        every { kisRestClient.submitOrder(any(), any(), any()) } returns KisOrderResponse(
            rtCd = "0", msgCd = "OK", msg1 = "OK",
            output = KisOrderResponse.Output(krxFwdgOrdOrgno = "00950", odno = "ODNO0001", ordTmd = "100000"),
        )
    }

    private fun newStompClient(): WebSocketStompClient {
        val client = WebSocketStompClient(StandardWebSocketClient())
        client.messageConverter = MappingJackson2MessageConverter()
        return client
    }

    private fun queueHandler(queue: LinkedBlockingQueue<Map<String, Any?>>) = object : StompFrameHandler {
        override fun getPayloadType(headers: StompHeaders): Type = Map::class.java
        @Suppress("UNCHECKED_CAST")
        override fun handleFrame(headers: StompHeaders, payload: Any?) {
            queue.offer(payload as Map<String, Any?>)
        }
    }

    init {
        beforeEach {
            cycleRepository.deleteAll()
            timeProvider.current = FixedTimeProviderConfig.DEFAULT_NOW
            stubKisDefaults()
        }

        test("CREATED 발행 시 /topic/trading/lifecycle + /topic/account BALANCE_INVALIDATED 동반 수신") {
            val lifecycleQueue = LinkedBlockingQueue<Map<String, Any?>>()
            val accountQueue = LinkedBlockingQueue<Map<String, Any?>>()

            val client = newStompClient()
            val session = client
                .connectAsync("ws://localhost:$port/ws", object : StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS)
            session.subscribe("/topic/trading/lifecycle", queueHandler(lifecycleQueue))
            session.subscribe("/topic/account", queueHandler(accountQueue))

            tradingService.create(
                TradingInput(
                    stockCode = "005930",
                    perBuyAmount = 1_000_000,
                    buyIntervalMin = 1,
                    splitSellRatio = BigDecimal("0.5"),
                    midwayProfitPct = BigDecimal("3.0"),
                    breakevenThresholdPct = BigDecimal("2.0"),
                    stopLossPct = BigDecimal("2.0"),
                )
            )

            val lifecycle = lifecycleQueue.poll(5, TimeUnit.SECONDS)
            val account = accountQueue.poll(5, TimeUnit.SECONDS)

            session.disconnect()
            client.stop()

            lifecycle?.get("type") shouldBe "CREATED"
            lifecycle?.get("stockCode") shouldBe "005930"
            account?.get("type") shouldBe "BALANCE_INVALIDATED"
        }
    }
}
