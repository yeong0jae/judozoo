package at.backend.account.application

import at.backend.platform.kis.client.KisRestClient
import at.backend.trading.domain.cycle.TradingCycleStatus
import at.backend.trading.infrastructure.repository.TradingCycleJpaRepository
import org.springframework.stereotype.Service

@Service
class AccountService(
    private val kisRestClient: KisRestClient,
    private val tradingCycleRepository: TradingCycleJpaRepository,
) {

    fun getBalance(): AccountBalanceResult {
        val cashBalance = kisRestClient.getBalance().output2.first().prvsRcdlExccAmt.toLong()
        val reservedAmount = tradingCycleRepository.findByStatusIn(ACTIVE_STATUSES)
            .sumOf { it.perBuyAmount * (MAX_BUY_ATTEMPT - it.buyAttempt) }
        return AccountBalanceResult(
            cashBalance = cashBalance,
            reservedAmount = reservedAmount,
            availableBalance = cashBalance - reservedAmount,
        )
    }

    data class AccountBalanceResult(
        val cashBalance: Long,
        val reservedAmount: Long,
        val availableBalance: Long,
    )

    companion object {
        private const val MAX_BUY_ATTEMPT = 3
        private val ACTIVE_STATUSES = listOf(
            TradingCycleStatus.INITIATED,
            TradingCycleStatus.BUYING,
            TradingCycleStatus.HOLDING,
        )
    }
}
