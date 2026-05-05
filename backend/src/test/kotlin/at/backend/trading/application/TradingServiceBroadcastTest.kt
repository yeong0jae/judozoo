package at.backend.trading.application

import at.backend.account.presentation.payload.BalanceInvalidatedPayload
import at.backend.common.test.FixedTimeProviderConfig
import at.backend.common.test.IntegrationTestBase
import at.backend.common.test.KisRestClientMockConfig
import at.backend.common.test.MessagingTemplateMockConfig
import at.backend.common.test.MutableTimeProvider
import at.backend.platform.kis.client.KisRestClient
import at.backend.platform.kis.client.response.KisBalanceResponse
import at.backend.platform.kis.client.response.KisCurrentPriceResponse
import at.backend.platform.kis.client.response.KisHolidayResponse
import at.backend.platform.kis.client.response.KisStockSearchResponse
import at.backend.trading.domain.TradingInput
import at.backend.trading.infrastructure.repository.TradingCycleJpaRepository
import at.backend.trading.presentation.payload.LifecyclePayload
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.messaging.simp.SimpMessagingTemplate
import java.math.BigDecimal

@Import(
    KisRestClientMockConfig::class,
    FixedTimeProviderConfig::class,
    MessagingTemplateMockConfig::class,
)
class TradingServiceBroadcastTest(
    @Autowired private val tradingService: TradingService,
    @Autowired private val cycleRepository: TradingCycleJpaRepository,
    @Autowired private val kisRestClient: KisRestClient,
    @Autowired private val timeProvider: MutableTimeProvider,
    @Autowired private val messagingTemplate: SimpMessagingTemplate,
) : IntegrationTestBase() {

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
    }

    init {
        beforeEach {
            clearMocks(kisRestClient, messagingTemplate, answers = false)
            cycleRepository.deleteAll()
            timeProvider.current = FixedTimeProviderConfig.DEFAULT_NOW
            stubKisDefaults()
        }

        context("사이클 생성 시 broadcast 사슬") {
            test("CREATED + BALANCE_INVALIDATED 동반 발행이 사슬 끝에서 messagingTemplate으로 도달한다") {
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

                verify {
                    messagingTemplate.convertAndSend(
                        "/topic/trading/lifecycle",
                        match<LifecyclePayload.Created> {
                            it.stockCode == "005930" && it.stockName == "삼성전자"
                        },
                    )
                }
                verify {
                    messagingTemplate.convertAndSend(
                        "/topic/account",
                        any<BalanceInvalidatedPayload>(),
                    )
                }
            }
        }
    }
}
