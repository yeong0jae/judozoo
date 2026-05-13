package at.backend.account.application

import at.backend.platform.kis.client.KisRestClient
import at.backend.platform.kis.config.KisProperties
import at.backend.trading.domain.cycle.TradingCycleStatus
import at.backend.trading.infrastructure.repository.TradingCycleJpaRepository
import org.springframework.stereotype.Service

@Service
class AccountService(
    private val kisRestClient: KisRestClient,
    private val tradingCycleRepository: TradingCycleJpaRepository,
    private val kisProperties: KisProperties,
) {

    fun getBalance(): AccountBalanceResult {
        val response = kisRestClient.getBalance()
        val cashBalance = response.output2.first().prvsRcdlExccAmt.toLong()
        val reservedAmount = tradingCycleRepository.findByAccountNoAndStatusIn(
            kisProperties.accountNo, TradingCycleStatus.ACTIVE
        ).sumOf { it.perBuyAmount * (MAX_BUY_ATTEMPT - it.buyAttempt) }
        return AccountBalanceResult(
            cashBalance = cashBalance,
            reservedAmount = reservedAmount,
            availableBalance = cashBalance - reservedAmount,
        )
    }

    /**
     * 현재 KIS 계좌에 남아있는 보유 주식 목록. 사이클 종료 후에도 남은 orphan 포지션 포함.
     * `hasActiveCycle`=true 인 종목은 시스템이 매매 중이라 수동 매도 시 사이클과 충돌하므로 UI에서 막아야 한다.
     */
    fun getHoldings(): List<HoldingResult> {
        val response = kisRestClient.getBalance()
        val activeStockCodes = tradingCycleRepository.findByAccountNoAndStatusIn(
            kisProperties.accountNo, TradingCycleStatus.OPEN
        ).mapTo(mutableSetOf()) { it.stockCode }
        return response.output1
            .mapNotNull { row ->
                val qty = row.hldgQty.toIntOrNull() ?: return@mapNotNull null
                if (qty <= 0) return@mapNotNull null
                val avgBuyPrice = row.pchsAvgPric.toBigDecimal().toLong()
                val currentPrice = row.prpr.toLong()
                val evalProfit = (currentPrice - avgBuyPrice) * qty
                val evalProfitRate = if (avgBuyPrice > 0L) {
                    (currentPrice - avgBuyPrice).toDouble() / avgBuyPrice
                } else 0.0
                HoldingResult(
                    stockCode = row.pdno,
                    stockName = row.prdtName,
                    qty = qty,
                    avgBuyPrice = avgBuyPrice,
                    currentPrice = currentPrice,
                    evalProfit = evalProfit,
                    evalProfitRate = evalProfitRate,
                    hasActiveCycle = row.pdno in activeStockCodes,
                )
            }
    }

    data class AccountBalanceResult(
        val cashBalance: Long,
        val reservedAmount: Long,
        val availableBalance: Long,
    )

    data class HoldingResult(
        val stockCode: String,
        val stockName: String,
        val qty: Int,
        val avgBuyPrice: Long,
        val currentPrice: Long,
        val evalProfit: Long,
        val evalProfitRate: Double,
        val hasActiveCycle: Boolean,
    )

    companion object {
        private const val MAX_BUY_ATTEMPT = 3
    }
}
