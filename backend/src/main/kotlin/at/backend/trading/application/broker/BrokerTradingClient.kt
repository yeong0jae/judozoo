package at.backend.trading.application.broker

import at.backend.market.domain.Bar
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

    /**
     * 트레이딩 사이클이 시그널 탐지에 사용하는 최근 분봉. 최신 → 과거 순.
     * 일반적으로 [0]은 진행 중 봉, [1]은 직전 닫힌 봉.
     */
    fun fetchBars(stockCode: String): List<Bar>

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
