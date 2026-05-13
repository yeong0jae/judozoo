package at.backend.account.application

import at.backend.library.exception.EntityNotFoundException
import at.backend.platform.kis.client.KisRestClient
import at.backend.platform.kis.config.KisProperties
import at.backend.trading.domain.cycle.TradingCycleStatus
import at.backend.trading.domain.order.OrderSide
import at.backend.trading.infrastructure.repository.TradingCycleJpaRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service

@Service
class AccountService(
    private val kisRestClient: KisRestClient,
    private val tradingCycleRepository: TradingCycleJpaRepository,
    private val kisProperties: KisProperties,
) {

    private val log = KotlinLogging.logger {}

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

    /**
     * 종목 보유 전량을 시장가로 매도 발송. 1회 발송 후 반환 — 체결 확정은 사용자가 재조회해 확인.
     * 활성 사이클이 있어도 API 레벨에선 막지 않고 WARN만 남긴다 (UI가 1차 가드).
     * 보유 0 이면 [EntityNotFoundException], KIS 거부는 KisOrderRejectedException 전파.
     */
    fun liquidate(stockCode: String): LiquidateResult {
        val holding = kisRestClient.getBalance().output1
            .firstOrNull { it.pdno == stockCode && (it.hldgQty.toIntOrNull() ?: 0) > 0 }
            ?: throw EntityNotFoundException("보유 종목이 없습니다: $stockCode")
        val qty = holding.hldgQty.toInt()
        val hasActive = tradingCycleRepository.findByAccountNoAndStockCodeAndStatusIn(
            kisProperties.accountNo, stockCode, TradingCycleStatus.OPEN
        ).isNotEmpty()
        if (hasActive) {
            log.warn { "수동 시장가 매도: 활성 사이클 있는 종목에 호출됨 stockCode=$stockCode, qty=$qty" }
        }
        val output = kisRestClient.requestOrder(stockCode, OrderSide.SELL.name, qty).output
            ?: error("KIS 매도 응답 output이 비어있습니다")
        return LiquidateResult(
            stockCode = stockCode,
            qty = qty,
            orderNo = output.odno,
            krxFwdgOrdOrgno = output.krxFwdgOrdOrgno,
        )
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

    data class LiquidateResult(
        val stockCode: String,
        val qty: Int,
        val orderNo: String,
        val krxFwdgOrdOrgno: String,
    )

    companion object {
        private const val MAX_BUY_ATTEMPT = 3
    }
}
