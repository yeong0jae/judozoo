package at.backend.market.application

import at.backend.market.domain.PriceTick
import at.backend.trading.application.broker.BrokerTradingClient
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.springframework.stereotype.Component

@Component
class PriceTickDataStream(
    private val broker: BrokerTradingClient,
    private val applicationScope: CoroutineScope,
) {

    private val _priceTicks = MutableSharedFlow<PriceTick>(extraBufferCapacity = 1024)
    val priceTicks: SharedFlow<PriceTick> = _priceTicks.asSharedFlow()

    @PostConstruct
    fun start() {
        applicationScope.launch {
            broker.priceTicks.collect { _priceTicks.tryEmit(it) }
        }
    }

    fun subscribe(stockCode: String) = broker.subscribePrice(stockCode)

    fun unsubscribe(stockCode: String) = broker.unsubscribePrice(stockCode)
}
