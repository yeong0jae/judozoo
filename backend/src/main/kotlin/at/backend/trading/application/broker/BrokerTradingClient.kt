package at.backend.trading.application.broker

import at.backend.trading.domain.order.ExecutionNotice
import at.backend.trading.domain.order.OrderSide
import kotlinx.coroutines.flow.SharedFlow
import java.time.LocalDate

interface BrokerTradingClient {
    val accountNo: String

    fun currentPrice(stockCode: String): Long
    fun availableCash(): Long
    fun holdings(): List<Holding>
    fun searchStock(stockCode: String): StockInfo?
    fun isMarketOpen(date: LocalDate): Boolean

    fun placeOrder(stockCode: String, side: OrderSide, qty: Int): PlacedOrder
    /** Kiwoom은 stockCode + odno만 필요, KIS는 orgno + odno만 필요 — adapter가 자기에게 필요한 인자만 사용. */
    fun cancelOrder(stockCode: String, orgno: String, odno: String)

    val executionNotices: SharedFlow<ExecutionNotice>
    fun subscribeExecutionNotices()
}

data class PlacedOrder(val orderNo: String, val orgno: String)

data class StockInfo(val code: String, val name: String)

data class Holding(
    val stockCode: String,
    val stockName: String,
    val qty: Int,
    val avgBuyPrice: Long,
    val currentPrice: Long,
)

class BrokerOrderRejectedException(val code: String, message: String) : RuntimeException(message)
