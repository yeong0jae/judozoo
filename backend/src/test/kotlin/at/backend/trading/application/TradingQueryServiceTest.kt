package at.backend.trading.application

import at.backend.common.test.FixedTimeProviderConfig
import at.backend.common.test.IntegrationTestBase
import at.backend.common.test.KisRestClientMockConfig
import at.backend.common.test.MutableTimeProvider
import at.backend.library.exception.EntityNotFoundException
import at.backend.platform.kis.client.KisRestClient
import at.backend.platform.kis.client.response.KisCurrentPriceResponse
import at.backend.trading.domain.cycle.CloseReason
import at.backend.trading.domain.cycle.TradingCycle
import at.backend.trading.domain.cycle.TradingCycleStatus
import at.backend.trading.infrastructure.repository.TradingCycleJpaRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.math.BigDecimal

@Import(KisRestClientMockConfig::class, FixedTimeProviderConfig::class)
class TradingQueryServiceTest(
    @Autowired private val tradingQueryService: TradingQueryService,
    @Autowired private val tradingCycleRepository: TradingCycleJpaRepository,
    @Autowired private val kisRestClient: KisRestClient,
    @Autowired private val timeProvider: MutableTimeProvider,
) : IntegrationTestBase() {

    private fun saveCycle(
        stockCode: String = "005930",
        stockName: String = "삼성전자",
        perBuyAmount: Long = 100_000L,
        status: TradingCycleStatus = TradingCycleStatus.INITIATED,
        closeReason: CloseReason? = null,
    ) = tradingCycleRepository.save(
        TradingCycle(
            accountNo = "00000000",
            stockCode = stockCode,
            stockName = stockName,
            perBuyAmount = perBuyAmount,
            splitSellRatio = BigDecimal("0.5"),
            breakevenThresholdPct = BigDecimal("0.5"),
            stopLossPct = BigDecimal("-2.0"),
            status = status,
            closeReason = closeReason,
        )
    )

    private fun stubCurrentPrice(price: String = "70000") {
        every { kisRestClient.getCurrentPrice(any()) } returns KisCurrentPriceResponse(
            output = KisCurrentPriceResponse.Output(stckPrpr = price)
        )
    }

    init {
        beforeEach {
            clearMocks(kisRestClient)
            tradingCycleRepository.deleteAll()
            timeProvider.current = FixedTimeProviderConfig.DEFAULT_NOW
        }

        context("findById") {
            test("사이클 상세 조회 시 DTO를 올바르게 반환한다") {
                val cycle = saveCycle()
                stubCurrentPrice("72000")

                val result = tradingQueryService.findById(cycle.id)

                result.cycleId shouldBe cycle.id
                result.stockCode shouldBe "005930"
                result.stockName shouldBe "삼성전자"
                result.status shouldBe "INITIATED"
                result.currentPrice shouldBe 72_000L
                result.holdingQty shouldBe 0
                result.averageBuyPrice shouldBe 0L
                result.orders shouldBe emptyList()
                result.executions shouldBe emptyList()
            }

            test("없는 id 조회 시 EntityNotFoundException 발생") {
                shouldThrow<EntityNotFoundException> {
                    tradingQueryService.findById(99L)
                }
            }
        }

        context("findActive") {
            test("활성 상태 사이클만 반환된다") {
                stubCurrentPrice()
                saveCycle(stockCode = "005930", status = TradingCycleStatus.INITIATED)
                saveCycle(stockCode = "035420", stockName = "NAVER", status = TradingCycleStatus.BUYING)
                saveCycle(stockCode = "035720", stockName = "카카오", status = TradingCycleStatus.CLOSED)

                val result = tradingQueryService.findActive()

                result shouldHaveSize 2
                result.map { it.stockCode }.toSet() shouldBe setOf("005930", "035420")
            }

            test("활성 사이클이 없으면 빈 목록을 반환한다") {
                saveCycle(status = TradingCycleStatus.CLOSED)

                val result = tradingQueryService.findActive()

                result shouldBe emptyList()
            }
        }

        context("findToday") {
            test("오늘 종료된 사이클만 반환한다") {
                val cycle = saveCycle(stockCode = "005930", status = TradingCycleStatus.CLOSED)
                saveCycle(stockCode = "035420", stockName = "NAVER", status = TradingCycleStatus.CLOSED)
                timeProvider.current = cycle.createdAt

                val result = tradingQueryService.findToday()

                result shouldHaveSize 2
            }

            test("진행 중 사이클(INITIATED/BUYING/HOLDING/LIQUIDATING)은 결과에서 제외된다") {
                saveCycle(stockCode = "005930", status = TradingCycleStatus.INITIATED)
                saveCycle(stockCode = "035420", status = TradingCycleStatus.BUYING)
                saveCycle(stockCode = "000660", status = TradingCycleStatus.HOLDING)
                saveCycle(stockCode = "035720", status = TradingCycleStatus.LIQUIDATING)
                val closed = saveCycle(stockCode = "207940", status = TradingCycleStatus.CLOSED)
                timeProvider.current = closed.createdAt

                val result = tradingQueryService.findToday()

                result.map { it.cycleId } shouldBe listOf(closed.id)
            }

            test("UNCLOSED로 마감된 사이클은 profitAmount/profitRate가 null로 반환된다") {
                val cycle = saveCycle(
                    stockCode = "005930",
                    status = TradingCycleStatus.CLOSED,
                    closeReason = CloseReason.UNCLOSED,
                )
                timeProvider.current = cycle.createdAt

                val result = tradingQueryService.findToday()

                result shouldHaveSize 1
                val r = result.first()
                r.cycleId shouldBe cycle.id
                r.profitAmount shouldBe null
                r.profitRate shouldBe null
            }

            test("사이클이 없으면 빈 목록을 반환한다") {
                val result = tradingQueryService.findToday()

                result shouldBe emptyList()
            }
        }
    }
}
