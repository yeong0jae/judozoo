package at.backend.platform.kis.adapter

import at.backend.library.time.atKstInstant
import at.backend.market.domain.Bar
import at.backend.market.domain.PriceTick
import at.backend.platform.kis.client.KisOrderRejectedException
import at.backend.platform.kis.client.KisRealQuotationClient
import at.backend.platform.kis.client.KisRestClient
import at.backend.platform.kis.client.KisWebSocketClient
import at.backend.platform.kis.config.KisProperties
import at.backend.trading.application.broker.BrokerOrderRejectedException
import at.backend.trading.application.broker.BrokerTradingClient
import at.backend.trading.application.broker.Holding
import at.backend.trading.application.broker.PlacedOrder
import at.backend.trading.application.broker.StockInfo
import at.backend.trading.domain.order.ExecutionNotice
import at.backend.trading.domain.order.OrderSide
import kotlinx.coroutines.flow.SharedFlow
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Component
@Profile("kis")
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

    override fun holdings(): List<Holding> =
        kisRestClient.getBalance().output1.mapNotNull { row ->
            val qty = row.hldgQty.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            Holding(
                stockCode = row.pdno,
                stockName = row.prdtName,
                qty = qty,
                avgBuyPrice = row.pchsAvgPric.toBigDecimal().toLong(),
                currentPrice = row.prpr.toLongOrNull() ?: 0L,
            )
        }

    override fun searchStock(stockCode: String): StockInfo? {
        val output = kisRealQuotationClient.searchStock(stockCode) ?: return null
        val name = output.prdtAbrvName?.takeIf { it.isNotBlank() } ?: return null
        return StockInfo(code = stockCode, name = name)
    }

    override fun isMarketOpen(date: LocalDate): Boolean =
        kisRealQuotationClient.checkHoliday(date).output.firstOrNull()?.opndYn == "Y"

    override fun fetchBars(stockCode: String): List<Bar> =
        kisRestClient.getBars(stockCode).output2.mapNotNull { row ->
            val date = runCatching { LocalDate.parse(row.stckBsopDate, DATE_FMT) }.getOrNull() ?: return@mapNotNull null
            val time = runCatching { LocalTime.parse(row.stckCntgHour, TIME_FMT) }.getOrNull() ?: return@mapNotNull null
            val openPrice = row.stckOprc.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val closePrice = row.stckPrpr.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val endTime = date.atKstInstant(time)
            Bar(
                stockCode = stockCode,
                openPrice = openPrice,
                closePrice = closePrice,
                startTime = endTime.minus(BAR_DURATION),
                endTime = endTime,
            )
        }

    override fun placeOrder(stockCode: String, side: OrderSide, qty: Int): PlacedOrder = try {
        val output = kisRestClient.requestOrder(stockCode, side.name, qty).output!!
        PlacedOrder(orderNo = output.odno, orgno = output.krxFwdgOrdOrgno)
    } catch (e: KisOrderRejectedException) {
        throw BrokerOrderRejectedException(code = e.msgCd, message = e.message ?: "주문 거부")
    }

    override fun cancelOrder(stockCode: String, orgno: String, odno: String) {
        // KIS는 stockCode 무관 — orgno + odno만 사용
        kisRestClient.cancelRemainder(orgno, odno)
    }

    override val executionNotices: SharedFlow<ExecutionNotice>
        get() = kisWebSocketClient.executionNotices

    override fun subscribeExecutionNotices() {
        kisWebSocketClient.subscribeExecutionNotice()
    }

    override val priceTicks: SharedFlow<PriceTick>
        get() = kisWebSocketClient.priceTicks

    override fun subscribePrice(stockCode: String) {
        kisWebSocketClient.subscribePrice(stockCode)
    }

    override fun unsubscribePrice(stockCode: String) {
        kisWebSocketClient.unsubscribePrice(stockCode)
    }

    companion object {
        private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
        private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmmss")
        private val BAR_DURATION: Duration = Duration.ofMinutes(3)
    }
}
