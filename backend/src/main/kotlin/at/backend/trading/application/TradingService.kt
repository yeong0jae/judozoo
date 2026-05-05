package at.backend.trading.application

import at.backend.library.exception.EntityNotFoundException
import at.backend.library.time.TimeProvider
import at.backend.trading.application.result.TradingCancelResult
import at.backend.trading.application.result.TradingCreatedResult
import at.backend.trading.domain.TradingInput
import at.backend.trading.domain.cycle.TradingCycle
import at.backend.trading.infrastructure.repository.TradingCycleJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

@Service
class TradingService(
    private val tradingValidator: TradingValidator,
    private val tradingCycleRepository: TradingCycleJpaRepository,
    private val timeProvider: TimeProvider,
    private val cycleOrchestrator: CycleOrchestrator,
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
        afterCommit { cycleOrchestrator.start(saved) }
        return TradingCreatedResult.from(saved)
    }

    @Transactional
    fun cancel(id: Long): TradingCancelResult {
        val cycle = tradingCycleRepository.findById(id)
            .orElseThrow { EntityNotFoundException("TradingCycle을 찾을 수 없습니다: $id") }
        cycle.requestCancel()
        tradingCycleRepository.save(cycle)
        afterCommit { cycleOrchestrator.cancel(cycle.id) }
        return TradingCancelResult.from(cycle)
    }

    private fun afterCommit(block: () -> Unit) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = block()
            })
        } else {
            block()
        }
    }
}
