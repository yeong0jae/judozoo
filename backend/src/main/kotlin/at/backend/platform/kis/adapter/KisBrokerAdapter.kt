package at.backend.platform.kis.adapter

import at.backend.platform.kis.client.KisOrderRejectedException
import at.backend.platform.kis.client.KisRealQuotationClient
import at.backend.platform.kis.client.KisRestClient
import at.backend.platform.kis.client.KisWebSocketClient
import at.backend.platform.kis.config.KisProperties
import at.backend.trading.application.broker.BrokerOrderRejectedException
import at.backend.trading.application.broker.BrokerTradingClient
import at.backend.trading.application.broker.PlacedOrder
import at.backend.trading.application.broker.StockInfo
import at.backend.trading.domain.order.ExecutionNotice
import at.backend.trading.domain.order.OrderSide
import kotlinx.coroutines.flow.SharedFlow
import org.springframework.stereotype.Component
import java.time.LocalDate

@Component
class KisBrokerAdapter(
    private val kisRestClient: KisRestClient,
    private val kisRealQuotationClient: KisRealQuotationClient,
    private val kisWebSocketClient: KisWebSocketClient,
    private val kisProperties: KisProperties,
) : BrokerTradingClient {

    override val accountNo: String
        get() = kisProperties.accountNo

    override fun currentPrice(stockCode: String): Long =
        kisRestClient.getCurrentPrice(stockCode).output.stckPrpr.toLong()

    override fun availableCash(): Long =
        kisRestClient.getBalance().output2.first().prvsRcdlExccAmt.toLong()

    override fun searchStock(stockCode: String): StockInfo? {
        val output = kisRealQuotationClient.searchStock(stockCode) ?: return null
        val name = output.prdtAbrvName?.takeIf { it.isNotBlank() } ?: return null
        return StockInfo(code = stockCode, name = name)
    }

    override fun isMarketOpen(date: LocalDate): Boolean =
        kisRealQuotationClient.checkHoliday(date).output.firstOrNull()?.opndYn == "Y"

    override fun placeOrder(stockCode: String, side: OrderSide, qty: Int): PlacedOrder = try {
        val output = kisRestClient.requestOrder(stockCode, side.name, qty).output!!
        PlacedOrder(orderNo = output.odno, orgno = output.krxFwdgOrdOrgno)
    } catch (e: KisOrderRejectedException) {
        throw BrokerOrderRejectedException(code = e.msgCd, message = e.message ?: "주문 거부")
    }

    override fun cancelOrder(orgno: String, odno: String) {
        kisRestClient.cancelRemainder(orgno, odno)
    }

    override val executionNotices: SharedFlow<ExecutionNotice>
        get() = kisWebSocketClient.executionNotices

    override fun subscribeExecutionNotices() {
        kisWebSocketClient.subscribeExecutionNotice()
    }
}
