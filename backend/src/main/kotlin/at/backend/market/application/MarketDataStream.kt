package at.backend.market.application

import at.backend.market.domain.PriceTick
import at.backend.platform.kis.client.KisWebSocketClient
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@Component
class MarketDataStream(
    private val webSocketClient: KisWebSocketClient,
    private val applicationScope: CoroutineScope,
) {

    private val subscriptionCounts = ConcurrentHashMap<String, AtomicInteger>()

    private val _priceTicks = MutableSharedFlow<PriceTick>(extraBufferCapacity = 1024)
    val priceTicks: SharedFlow<PriceTick> = _priceTicks.asSharedFlow()

    @PostConstruct
    fun start() {
        applicationScope.launch {
            webSocketClient.priceTicks.collect { _priceTicks.tryEmit(it) }
        }
    }

    fun subscribe(stockCode: String) {
        val count = subscriptionCounts.computeIfAbsent(stockCode) { AtomicInteger(0) }
        if (count.incrementAndGet() == 1) {
            webSocketClient.subscribePrice(stockCode)
        }
    }

    fun unsubscribe(stockCode: String) {
        val count = subscriptionCounts[stockCode] ?: return
        if (count.decrementAndGet() <= 0) {
            subscriptionCounts.remove(stockCode)
            webSocketClient.unsubscribePrice(stockCode)
        }
    }

    fun activeStockCodes(): Set<String> = subscriptionCounts.keys.toSet()

    fun reset() {
        subscriptionCounts.clear()
    }
}
