package at.backend.platform.kiwoom.adapter

import at.backend.trading.application.broker.BrokerTradingClient
import at.backend.trading.application.broker.PlacedOrder
import at.backend.trading.application.broker.StockInfo
import at.backend.trading.domain.order.ExecutionNotice
import at.backend.trading.domain.order.OrderSide
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * Phase 10-A stub. 모든 메서드는 NotImplementedError를 던진다.
 * 10-B에서 KiwoomTradingClient + KiwoomExecutionNoticeClient 위에 실구현으로 교체.
 *
 * 이 빈은 `kiwoom` profile에서만 활성화되며, BrokerActivationGuard가 잘못된 조합을
 * 컨텍스트 시작 단계에서 차단한다. 부팅은 성공하지만 broker 호출이 일어나는 순간 실패.
 */
@Component
@Profile("kiwoom")
class KiwoomBrokerAdapter : BrokerTradingClient {

    override val accountNo: String
        get() = notYet("accountNo")

    override fun currentPrice(stockCode: String): Long = notYet("currentPrice")
    override fun availableCash(): Long = notYet("availableCash")
    override fun searchStock(stockCode: String): StockInfo? = notYet("searchStock")
    override fun isMarketOpen(date: LocalDate): Boolean = notYet("isMarketOpen")
    override fun placeOrder(stockCode: String, side: OrderSide, qty: Int): PlacedOrder = notYet("placeOrder")
    override fun cancelOrder(orgno: String, odno: String) {
        notYet("cancelOrder")
    }

    override val executionNotices: SharedFlow<ExecutionNotice> = MutableSharedFlow<ExecutionNotice>().asSharedFlow()

    override fun subscribeExecutionNotices() {
        notYet("subscribeExecutionNotices")
    }

    private fun notYet(method: String): Nothing =
        throw NotImplementedError("KiwoomBrokerAdapter.$method — Phase 10-B에서 구현 예정")
}
