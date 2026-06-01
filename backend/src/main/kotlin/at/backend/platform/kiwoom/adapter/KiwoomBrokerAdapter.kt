package at.backend.platform.kiwoom.adapter

import at.backend.market.domain.Bar
import at.backend.market.domain.PriceTick
import at.backend.platform.kiwoom.client.KiwoomOrderRejectedException
import at.backend.platform.kiwoom.client.KiwoomTradingClient
import at.backend.platform.kiwoom.client.KiwoomWebSocketClient
import at.backend.platform.kiwoom.config.KiwoomTradingProperties
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
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    private val webSocketClient: KiwoomWebSocketClient,
    private val tradingProperties: KiwoomTradingProperties,
) : BrokerTradingClient {

    override val accountNo: String
        get() = tradingProperties.accountNo

    override fun currentPrice(stockCode: String): Long =
        tradingClient.fetchStockInfo(stockCode)?.currentPrice ?: 0L

    override fun availableCash(): Long = tradingClient.fetchAvailableCash()

    override fun holdings(): List<Holding> = tradingClient.fetchHoldingsRaw().mapNotNull { row ->
        val qty = row.rmnd_qty.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        // Kiwoom kt00004는 "A005930" 같이 prefix 붙여 보냄 — 다른 곳과 일관성 위해 prefix 제거
        val code = row.stk_cd.removePrefix("A")
        // 잔고 응답(cur_prc)은 KRX 종가만 반환 — NXT 시간대를 위해 종목 시세(ka10001)로 재조회.
        val quotePrice = runCatching { currentPrice(code) }.getOrNull()?.takeIf { it > 0L }
        Holding(
            stockCode = code,
            stockName = row.stk_nm,
            qty = qty,
            avgBuyPrice = parseKiwoomLong(row.avg_prc),
            currentPrice = quotePrice ?: parseKiwoomLong(row.cur_prc),
        )
    }

    /** Kiwoom 가격/금액은 +/- 부호 접두 가능 — 부호 제거 후 절대값. */
    private fun parseKiwoomLong(s: String): Long =
        s.trim().removePrefix("+").removePrefix("-").toLongOrNull() ?: 0L

    companion object {
        private val CNTR_TM_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
        private val BAR_DURATION: Duration = Duration.ofMinutes(3)
    }

    override fun searchStock(stockCode: String): StockInfo? {
        val info = tradingClient.fetchStockInfo(stockCode) ?: return null
        return StockInfo(code = stockCode, name = info.stockName)
    }

    override fun isMarketOpen(date: LocalDate): Boolean =
        date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY

    override fun fetchBars(stockCode: String): List<Bar> =
        tradingClient.fetchMinuteBars(stockCode).mapNotNull { row ->
            val endTime = runCatching {
                LocalDateTime.parse(row.cntr_tm, CNTR_TM_FMT).atZone(KST).toInstant()
            }.getOrNull() ?: return@mapNotNull null
            val openPrice = parseKiwoomInt(row.open_pric) ?: return@mapNotNull null
            val closePrice = parseKiwoomInt(row.cur_prc) ?: return@mapNotNull null
            Bar(
                stockCode = stockCode,
                openPrice = openPrice,
                closePrice = closePrice,
                startTime = endTime.minus(BAR_DURATION),
                endTime = endTime,
            )
        }

    private fun parseKiwoomInt(s: String): Int? =
        s.trim().removePrefix("+").removePrefix("-").toIntOrNull()?.takeIf { it > 0 }

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
        get() = webSocketClient.executionNotices

    override fun subscribeExecutionNotices() {
        webSocketClient.subscribeExecution()
    }

    override val priceTicks: SharedFlow<PriceTick>
        get() = webSocketClient.priceTicks

    override fun subscribePrice(stockCode: String) {
        webSocketClient.subscribePrice(stockCode)
    }

    override fun unsubscribePrice(stockCode: String) {
        webSocketClient.unsubscribePrice(stockCode)
    }
}
