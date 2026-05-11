package at.backend.account.application

import at.backend.platform.kis.client.KisRestClient
import at.backend.platform.kis.config.KisProperties
import at.backend.trading.domain.cycle.TradingCycleStatus
import at.backend.trading.infrastructure.repository.TradingCycleJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class AccountService(
    private val kisRestClient: KisRestClient,
    private val tradingCycleRepository: TradingCycleJpaRepository,
    private val kisProperties: KisProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun getBalance(): AccountBalanceResult {
        val response = runCatching { kisRestClient.getBalance() }
            .onFailure { log.error("KIS 잔고 호출 실패 accountNo={}", kisProperties.accountNo, it) }
            .getOrThrow()
        log.info("KIS 잔고 응답: output2={}", response.output2)
        val cashBalance = response.output2.first().prvsRcdlExccAmt.toLong()
        val reservedAmount = tradingCycleRepository.findByAccountNoAndStatusIn(kisProperties.accountNo, TradingCycleStatus.ACTIVE)
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
    }
}
