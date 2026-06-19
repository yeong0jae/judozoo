package at.backend.account.application

import at.backend.library.exception.EntityNotFoundException
import at.backend.trading.TradingProperties
import at.backend.trading.application.broker.BrokerTradingClient
import at.backend.trading.domain.cycle.TradingCycleStatus
import at.backend.trading.domain.order.OrderSide
import at.backend.trading.infrastructure.repository.TradingCycleJpaRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import kotlin.math.roundToLong

@Service
class AccountService(
    private val broker: BrokerTradingClient,
    private val tradingCycleRepository: TradingCycleJpaRepository,
    private val tradingProperties: TradingProperties,
) {

    private val log = KotlinLogging.logger {}

    fun getBalance(): AccountBalanceResult {
        val cashBalance = broker.availableCash()
        val reservedAmount = tradingCycleRepository.findByAccountNoAndStatusIn(
            broker.accountNo, TradingCycleStatus.ACTIVE
        ).sumOf { it.reservedCash() }
        return AccountBalanceResult(
            cashBalance = cashBalance,
            reservedAmount = reservedAmount,
            availableBalance = cashBalance - reservedAmount,
        )
    }

    /**
     * 현재 계좌에 남아있는 보유 주식 목록. 사이클 종료 후에도 남은 orphan 포지션 포함.
     * `hasActiveCycle`=true 인 종목은 시스템이 매매 중이라 수동 매도 시 사이클과 충돌하므로 UI에서 막아야 한다.
     */
    fun getHoldings(): List<HoldingResult> {
        val activeStockCodes = tradingCycleRepository.findByAccountNoAndStatusIn(
            broker.accountNo, TradingCycleStatus.OPEN
        ).mapTo(mutableSetOf()) { it.stockCode }
        // 매도 시 발생할 수수료·세금(sellCostRate)을 반영한 손익분기가 — 엔진 calculateBuyPrice와 동일 컨벤션
        val sellCostRate = tradingProperties.sellCostRate.toDouble()
        return broker.holdings().map { h ->
            val breakEvenPrice = h.avgBuyPrice * (1.0 + sellCostRate)
            val evalProfit = ((h.currentPrice - breakEvenPrice) * h.qty).roundToLong()
            val evalProfitRate = if (h.avgBuyPrice > 0L) {
                (h.currentPrice - breakEvenPrice) / h.avgBuyPrice
            } else 0.0
            HoldingResult(
                stockCode = h.stockCode,
                stockName = h.stockName,
                qty = h.qty,
                avgBuyPrice = h.avgBuyPrice,
                currentPrice = h.currentPrice,
                evalProfit = evalProfit,
                evalProfitRate = evalProfitRate,
                hasActiveCycle = h.stockCode in activeStockCodes,
            )
        }
    }

    /**
     * 종목 보유 전량을 시장가로 매도 발송. 1회 발송 후 반환 — 체결 확정은 사용자가 재조회해 확인.
     * 활성 사이클이 있어도 API 레벨에선 막지 않고 WARN만 남긴다 (UI가 1차 가드).
     * 보유 0 이면 [EntityNotFoundException], broker 거부는 BrokerOrderRejectedException 전파.
     */
    fun liquidate(stockCode: String): LiquidateResult {
        val holding = broker.holdings().firstOrNull { it.stockCode == stockCode && it.qty > 0 }
            ?: throw EntityNotFoundException("보유 종목이 없습니다: $stockCode")
        val qty = holding.qty
        val hasActive = tradingCycleRepository.findByAccountNoAndStockCodeAndStatusIn(
            broker.accountNo, stockCode, TradingCycleStatus.OPEN
        ).isNotEmpty()
        if (hasActive) {
            log.warn { "수동 시장가 매도: 활성 사이클 있는 종목에 호출됨 stockCode=$stockCode, qty=$qty" }
        }
        val placed = broker.placeOrder(stockCode, OrderSide.SELL, qty)
        return LiquidateResult(
            stockCode = stockCode,
            qty = qty,
            orderNo = placed.orderNo,
            krxFwdgOrdOrgno = placed.orgno,
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
}
