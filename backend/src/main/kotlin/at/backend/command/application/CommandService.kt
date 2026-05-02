package at.backend.command.application

import at.backend.command.application.response.CommandCreatedResponse
import at.backend.command.domain.AlreadyClosedException
import at.backend.command.domain.CommandInput
import at.backend.library.exception.EntityNotFoundException
import at.backend.trading.domain.cycle.TradingCycle
import at.backend.trading.domain.cycle.TradingCycleStatus
import at.backend.trading.infrastructure.repository.TradingCycleJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class CommandService(
    private val commandValidator: CommandValidator,
    private val tradingCycleRepository: TradingCycleJpaRepository,
) {

    @Transactional
    fun create(input: CommandInput): CommandCreatedResponse {
        val now = LocalDateTime.now()
        val stockName = commandValidator.validate(input, now)
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
        return CommandCreatedResponse(id = saved.id)
    }

    @Transactional
    fun cancel(id: Long) {
        val cycle = tradingCycleRepository.findById(id)
            .orElseThrow { EntityNotFoundException("TradingCycle not found: $id") }

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
    }
}
