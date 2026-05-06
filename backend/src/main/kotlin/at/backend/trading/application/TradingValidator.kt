package at.backend.trading.application

import at.backend.platform.kis.client.KisRestClient
import at.backend.trading.domain.TradingInput
import at.backend.trading.domain.TradingValidationException
import at.backend.trading.domain.cycle.TradingCycle
import at.backend.trading.domain.cycle.TradingCycleStatus
import at.backend.trading.infrastructure.repository.TradingCycleJpaRepository
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.LocalTime

@Component
class TradingValidator(
    private val kisRestClient: KisRestClient,
    private val tradingCycleRepository: TradingCycleJpaRepository,
    private val commandGate: CommandGate,
) {

    fun validate(input: TradingInput, now: LocalDateTime): String {
        validateGate()
        val stockName = resolveStockName(input.stockCode)
        validatePrice(input.stockCode, input.perBuyAmount)
        validateBalance(input.perBuyAmount)
        validateNoDuplicate(input.stockCode)
        validateHoliday(now)
        validateTradingHours(now.toLocalTime())
        validateCutoff(input.buyIntervalMin, now.toLocalTime())
        return stockName
    }

    private fun validateGate() {
        if (!commandGate.isOpen()) {
            throw TradingValidationException(TradingValidationException.ErrorCode.COMMAND_GATE_CLOSED)
        }
    }

    private fun resolveStockName(stockCode: String): String {
        val output = kisRestClient.searchStock(stockCode).output
        return output.prdtAbrvName.takeIf { it.isNotBlank() }
            ?: throw TradingValidationException(TradingValidationException.ErrorCode.STOCK_NOT_FOUND)
    }

    private fun validatePrice(stockCode: String, perBuyAmount: Long) {
        val price = kisRestClient.getCurrentPrice(stockCode).output.stckPrpr.toLong()
        if (price > perBuyAmount) throw TradingValidationException(TradingValidationException.ErrorCode.PRICE_BELOW_ONE_SHARE)
    }

    private fun validateBalance(perBuyAmount: Long) {
        // 활성 사이클(INITIATED/BUYING/HOLDING)의 미발사 회차 분을 reserved로 차감 — AccountService.getBalance와 동일 공식.
        // 새 명령은 3회차(MAX_BUY_ATTEMPT) 전부 커밋 가능해야 통과 — 화면 가용잔고와 일관된 기준.
        val balance = kisRestClient.getBalance().output2.first().prvsRcdlExccAmt.toLong()
        val reserved = tradingCycleRepository.findByStatusIn(ACTIVE_STATUSES)
            .sumOf { it.perBuyAmount * (TradingCycle.MAX_BUY_ATTEMPT - it.buyAttempt) }
        val available = balance - reserved
        val newCommitment = perBuyAmount * TradingCycle.MAX_BUY_ATTEMPT
        if (available < newCommitment) throw TradingValidationException(TradingValidationException.ErrorCode.INSUFFICIENT_BALANCE)
    }

    private fun validateNoDuplicate(stockCode: String) {
        val existing = tradingCycleRepository.findByStockCodeAndStatusIn(stockCode, ACTIVE_STATUSES)
        if (existing.isNotEmpty()) throw TradingValidationException(TradingValidationException.ErrorCode.DUPLICATE_COMMAND)
    }

    private fun validateCutoff(buyIntervalMin: Int, now: LocalTime) {
        val cutoff = CUTOFF_BASE.minusMinutes((buyIntervalMin * 2).toLong())
        if (now.isAfter(cutoff)) throw TradingValidationException(TradingValidationException.ErrorCode.CUTOFF_PASSED)
    }

    private fun validateHoliday(now: LocalDateTime) {
        val isMarketOpen = kisRestClient.checkHoliday(now.toLocalDate()).output.firstOrNull()?.opndYn == "Y"
        if (!isMarketOpen) throw TradingValidationException(TradingValidationException.ErrorCode.HOLIDAY)
    }

    private fun validateTradingHours(now: LocalTime) {
        if (now.isBefore(TRADING_START) || now.isAfter(TRADING_END)) {
            throw TradingValidationException(TradingValidationException.ErrorCode.OUT_OF_TRADING_HOURS)
        }
    }

    companion object {
        private val TRADING_START = LocalTime.of(9, 0)
        private val TRADING_END = LocalTime.of(15, 30)
        private val CUTOFF_BASE = LocalTime.of(15, 20)
        private val ACTIVE_STATUSES = listOf(
            TradingCycleStatus.INITIATED,
            TradingCycleStatus.BUYING,
            TradingCycleStatus.HOLDING,
        )
    }
}
