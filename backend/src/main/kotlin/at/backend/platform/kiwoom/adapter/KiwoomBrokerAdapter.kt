package at.backend.platform.kiwoom.adapter

import at.backend.platform.kiwoom.client.KiwoomExecutionWebSocketClient
import at.backend.platform.kiwoom.client.KiwoomOrderRejectedException
import at.backend.platform.kiwoom.client.KiwoomTradingClient
import at.backend.platform.kiwoom.config.KiwoomTradingProperties
import at.backend.trading.application.broker.BrokerOrderRejectedException
import at.backend.trading.application.broker.BrokerTradingClient
import at.backend.trading.application.broker.PlacedOrder
import at.backend.trading.application.broker.StockInfo
import at.backend.trading.domain.order.ExecutionNotice
import at.backend.trading.domain.order.OrderSide
import kotlinx.coroutines.flow.SharedFlow
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Phase 10-B 실구현. Kiwoom OpenAPI 트레이딩 + 실시간 주문체결 통보 위에서 동작.
 *
 * 휴장 여부: Kiwoom에 전용 API가 없어 평일(Mon~Fri) 단순 체크로만 사전 차단.
 * 공휴일은 매수 거부 응답에 위임(매수 1건 fail → 사이클 자동 종료 후 사용자 인지).
 */
@Component
@Profile("kiwoom")
class KiwoomBrokerAdapter(
    private val tradingClient: KiwoomTradingClient,
    private val executionWsClient: KiwoomExecutionWebSocketClient,
    private val tradingProperties: KiwoomTradingProperties,
) : BrokerTradingClient {

    override val accountNo: String
        get() = tradingProperties.accountNo

    override fun currentPrice(stockCode: String): Long =
        tradingClient.fetchStockInfo(stockCode)?.currentPrice ?: 0L

    override fun availableCash(): Long = tradingClient.fetchAvailableCash()

    override fun searchStock(stockCode: String): StockInfo? {
        val info = tradingClient.fetchStockInfo(stockCode) ?: return null
        return StockInfo(code = stockCode, name = info.stockName)
    }

    override fun isMarketOpen(date: LocalDate): Boolean =
        date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY

    override fun placeOrder(stockCode: String, side: OrderSide, qty: Int): PlacedOrder = try {
        val ordNo = when (side) {
            OrderSide.BUY -> tradingClient.placeBuyOrder(stockCode, qty)
            OrderSide.SELL -> tradingClient.placeSellOrder(stockCode, qty)
        }
        // Kiwoom은 KIS의 orgno(원장 번호) 개념 미사용 — orderNo 단독으로 취소 가능. orgno는 빈 문자열.
        PlacedOrder(orderNo = ordNo, orgno = "")
    } catch (e: KiwoomOrderRejectedException) {
        throw BrokerOrderRejectedException(code = e.code, message = e.message ?: "주문 거부")
    }

    override fun cancelOrder(stockCode: String, orgno: String, odno: String) {
        tradingClient.cancelOrder(stockCode = stockCode, originalOrderNo = odno)
    }

    override val executionNotices: SharedFlow<ExecutionNotice>
        get() = executionWsClient.executionNotices

    override fun subscribeExecutionNotices() {
        executionWsClient.subscribe()
    }
}
