package at.backend.trading.application

import at.backend.library.exception.EntityNotFoundException
import at.backend.library.time.TimeProvider
import at.backend.library.time.toInstantKst
import at.backend.trading.application.broker.BrokerTradingClient
import at.backend.trading.application.result.TradingCancelResult
import at.backend.trading.application.result.TradingCreatedResult
import at.backend.trading.domain.TradingInput
import at.backend.trading.domain.cycle.TradingCycle
import at.backend.trading.domain.event.TradingCycleCreated
import at.backend.trading.infrastructure.repository.TradingCycleJpaRepository
import org.springframework.context.ApplicationEventPublisher
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
    private val eventPublisher: ApplicationEventPublisher,
    private val broker: BrokerTradingClient,
) {

    @Transactional
    fun create(input: TradingInput): TradingCreatedResult {
        val now = timeProvider.now()
        val (stockName, currentPrice) = tradingValidator.validate(input, now)
        // perBuyAmount는 NOT NULL 컬럼이라 신규 사이클도 추정치(개수 × 검증 시점 현재가)를 채워둔다.
        // 실제 매수 qty는 OrderService가 perBuyQty를 그대로 사용한다.
        val cycle = TradingCycle(
            accountNo = broker.accountNo,
            stockCode = input.stockCode,
            stockName = stockName,
            perBuyAmount = input.perBuyQty.toLong() * currentPrice,
            perBuyQty = input.perBuyQty,
            splitSellRatio = input.splitSellRatio,
            breakevenThresholdPct = input.breakevenThresholdPct,
            stopLossPct = input.stopLossPct.negate(),
        )
        val saved = tradingCycleRepository.save(cycle)

        afterCommit {
            cycleOrchestrator.start(saved)
            eventPublisher.publishEvent(
                TradingCycleCreated(
                    cycleId = saved.id,
                    stockCode = saved.stockCode,
                    stockName = saved.stockName,
                    ts = now.toInstantKst(),
                )
            )
        }
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
