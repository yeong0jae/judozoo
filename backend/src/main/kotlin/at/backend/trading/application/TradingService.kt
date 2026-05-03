package at.backend.trading.application

import at.backend.library.time.TimeProvider
import at.backend.trading.application.result.TradingCancelResult
import at.backend.trading.application.result.TradingCreatedResult
import at.backend.trading.domain.AlreadyClosedException
import at.backend.trading.domain.TradingInput
import at.backend.library.exception.EntityNotFoundException
import at.backend.trading.domain.cycle.TradingCycle
import at.backend.trading.domain.cycle.TradingCycleStatus
import at.backend.trading.infrastructure.repository.TradingCycleJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TradingService(
    private val tradingValidator: TradingValidator,
    private val tradingCycleRepository: TradingCycleJpaRepository,
    private val timeProvider: TimeProvider,
) {

    @Transactional
    fun create(input: TradingInput): TradingCreatedResult {
        val now = timeProvider.now()
        val stockName = tradingValidator.validate(input, now)
        val cycle = TradingCycle(
            stockCode = input.stockCode,
            stockName = stockName,
            perBuyAmount = input.perBuyAmount,
            buyIntervalMin = input.buyIntervalMin,
            splitSellRatio = input.splitSellRatio,
            midwayProfitPct = input.midwayProfitPct,
            breakevenThresholdPct = input.breakevenThresholdPct,
            stopLossPct = input.stopLossPct.negate(),
        )
        val saved = tradingCycleRepository.save(cycle)
        return TradingCreatedResult.from(saved)
    }

    @Transactional
    fun cancel(id: Long): TradingCancelResult {
        val cycle = tradingCycleRepository.findById(id)
            .orElseThrow { EntityNotFoundException("TradingCycle을 찾을 수 없습니다: $id") }

        when (cycle.status) {
            TradingCycleStatus.INITIATED,
            TradingCycleStatus.BUYING,
            TradingCycleStatus.HOLDING -> {
                cycle.status = TradingCycleStatus.LIQUIDATING
                tradingCycleRepository.save(cycle)
            }

            TradingCycleStatus.LIQUIDATING -> Unit
            TradingCycleStatus.CLOSED -> throw AlreadyClosedException(id)
        }
        return TradingCancelResult.from(cycle)
    }
}
