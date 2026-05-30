package at.backend.trading.application

import at.backend.trading.application.broker.BrokerTradingClient
import at.backend.trading.domain.TradingInput
import at.backend.trading.domain.TradingValidationException
import at.backend.trading.domain.cycle.TradingCycleStatus
import at.backend.trading.infrastructure.repository.TradingCycleJpaRepository
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.LocalTime

@Component
class TradingValidator(
    private val broker: BrokerTradingClient,
    private val tradingCycleRepository: TradingCycleJpaRepository,
) {

    /** 검증 결과로 (종목명, 검증 시점 현재가)를 반환 — 생성 시 perBuyAmount 추정치로 활용한다. */
    fun validate(input: TradingInput, now: LocalDateTime): Pair<String, Long> {
        val stockName = resolveStockName(input.stockCode)
        val currentPrice = broker.currentPrice(input.stockCode)
        validateBalance(input.perBuyQty, currentPrice)
        validateNoDuplicate(input.stockCode)
        validateHoliday(now)
        validateTradingHours(now.toLocalTime())
        validateCutoff(now.toLocalTime())
        return stockName to currentPrice
    }

    private fun resolveStockName(stockCode: String): String {
        val info = broker.searchStock(stockCode)
            ?: throw TradingValidationException(TradingValidationException.ErrorCode.STOCK_NOT_FOUND)
        return info.name
    }

    private fun validateBalance(perBuyQty: Int, currentPrice: Long) {
        val balance = broker.availableCash()
        val required = perBuyQty.toLong() * currentPrice
        if (required > balance) {
            throw TradingValidationException(TradingValidationException.ErrorCode.INSUFFICIENT_BALANCE)
        }
    }

    private fun validateNoDuplicate(stockCode: String) {
        val existing = tradingCycleRepository.findByAccountNoAndStockCodeAndStatusIn(
            broker.accountNo, stockCode, TradingCycleStatus.ACTIVE
        )
        if (existing.isNotEmpty()) throw TradingValidationException(TradingValidationException.ErrorCode.DUPLICATE_COMMAND)
    }

    private fun validateCutoff(now: LocalTime) {
        if (now.isAfter(CUTOFF_BASE)) throw TradingValidationException(TradingValidationException.ErrorCode.CUTOFF_PASSED)
    }

    private fun validateHoliday(now: LocalDateTime) {
        if (!broker.isMarketOpen(now.toLocalDate())) {
            throw TradingValidationException(TradingValidationException.ErrorCode.HOLIDAY)
        }
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
    }
}
