package at.backend.trading.application.broker

import at.backend.trading.domain.order.ExecutionNotice
import at.backend.trading.domain.order.OrderSide
import kotlinx.coroutines.flow.SharedFlow
import java.time.LocalDate

interface BrokerTradingClient {
    val accountNo: String

    fun currentPrice(stockCode: String): Long
    fun availableCash(): Long
    fun searchStock(stockCode: String): StockInfo?
    fun isMarketOpen(date: LocalDate): Boolean

    fun placeOrder(stockCode: String, side: OrderSide, qty: Int): PlacedOrder
    fun cancelOrder(orgno: String, odno: String)

    val executionNotices: SharedFlow<ExecutionNotice>
    fun subscribeExecutionNotices()
}

data class PlacedOrder(val orderNo: String, val orgno: String)

data class StockInfo(val code: String, val name: String)

class BrokerOrderRejectedException(val code: String, message: String) : RuntimeException(message)
