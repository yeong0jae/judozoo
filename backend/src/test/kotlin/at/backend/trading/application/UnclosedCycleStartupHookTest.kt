package at.backend.trading.application

import at.backend.common.test.FixedTimeProviderConfig
import at.backend.common.test.IntegrationTestBase
import at.backend.common.test.KisRestClientMockConfig
import at.backend.common.test.MutableTimeProvider
import at.backend.trading.domain.cycle.CloseReason
import at.backend.trading.domain.cycle.TradingCycle
import at.backend.trading.domain.cycle.TradingCycleStatus
import at.backend.trading.infrastructure.repository.TradingCycleJpaRepository
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.math.BigDecimal

@Import(KisRestClientMockConfig::class, FixedTimeProviderConfig::class)
class UnclosedCycleStartupHookTest(
    @Autowired private val hook: UnclosedCycleStartupHook,
    @Autowired private val cycleRepository: TradingCycleJpaRepository,
    @Autowired private val timeProvider: MutableTimeProvider,
) : IntegrationTestBase() {

    private fun saveCycle(status: TradingCycleStatus): TradingCycle =
        cycleRepository.save(
            TradingCycle(
                stockCode = "005930",
                stockName = "삼성전자",
                perBuyAmount = 100_000L,
                buyIntervalMin = 3,
                splitSellRatio = BigDecimal("0.5"),
                midwayProfitPct = BigDecimal("1.5"),
                breakevenThresholdPct = BigDecimal("0.5"),
                stopLossPct = BigDecimal("-0.02"),
                status = status,
            )
        )

    init {
        beforeEach {
            cycleRepository.deleteAll()
            timeProvider.current = FixedTimeProviderConfig.DEFAULT_NOW
        }

        context("재기동 시 활성 사이클 일괄 마감") {
            test("INITIATED/BUYING/HOLDING/LIQUIDATING 사이클 모두 CLOSED(UNCLOSED)로 마감된다") {
                val initiated = saveCycle(TradingCycleStatus.INITIATED)
                val buying = saveCycle(TradingCycleStatus.BUYING)
                val holding = saveCycle(TradingCycleStatus.HOLDING)
                val liquidating = saveCycle(TradingCycleStatus.LIQUIDATING)

                hook.closeUnclosedCycles()

                listOf(initiated, buying, holding, liquidating).forEach { saved ->
                    val refreshed = cycleRepository.findById(saved.id).get()
                    refreshed.status shouldBe TradingCycleStatus.CLOSED
                    refreshed.closeReason shouldBe CloseReason.UNCLOSED
                    refreshed.closedAt shouldBe FixedTimeProviderConfig.DEFAULT_NOW
                }
            }

            test("이미 CLOSED인 사이클은 변경되지 않는다") {
                val closed = saveCycle(TradingCycleStatus.LIQUIDATING).also {
                    it.close(CloseReason.MARKET_CLOSE, FixedTimeProviderConfig.DEFAULT_NOW.minusHours(1))
                    cycleRepository.save(it)
                }

                hook.closeUnclosedCycles()

                val refreshed = cycleRepository.findById(closed.id).get()
                refreshed.closeReason shouldBe CloseReason.MARKET_CLOSE
            }
        }
    }
}
