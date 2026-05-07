package at.backend.market.application

import at.backend.market.domain.PriceTick
import at.backend.market.domain.event.MarketModeChanged
import at.backend.platform.kis.client.KisRestClient
import at.backend.platform.kis.client.KisWebSocketClient
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

@Component
class MarketDataStream(
    private val webSocketClient: KisWebSocketClient,
    private val restClient: KisRestClient,
    private val applicationScope: CoroutineScope,
    private val eventPublisher: ApplicationEventPublisher,
    @Value("\${trading.market.poll-interval-millis:1000}") private val pollIntervalMillis: Long,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // 몇 개의 사이클이 해당 종목을 구독하는지 카운트
    private val subscriptionCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val pollingJob = AtomicReference<Job?>(null)

    private val _priceTicks = MutableSharedFlow<PriceTick>(extraBufferCapacity = 1024)
    private val _mode = MutableSharedFlow<MarketMode>(replay = 1, extraBufferCapacity = 16)

    val priceTicks: SharedFlow<PriceTick> = _priceTicks.asSharedFlow()
    val mode: SharedFlow<MarketMode> = _mode.asSharedFlow()

    @PostConstruct
    fun start() {
        _mode.tryEmit(MarketMode.WS)
        applicationScope.launch {
            webSocketClient.priceTicks.collect { _priceTicks.tryEmit(it) }
        }
        applicationScope.launch {
            webSocketClient.connectionState.collect { connected ->
                if (connected) onWsConnected() else onWsDisconnected()
            }
        }
    }

    fun subscribe(stockCode: String) {
        val count = subscriptionCounts.computeIfAbsent(stockCode) { AtomicInteger(0) }
        if (count.incrementAndGet() == 1) { // 해당 종목을 구독하는 첫 사이클만 WS 구독 요청
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

    fun currentMode(): MarketMode = _mode.replayCache.lastOrNull() ?: MarketMode.WS

    fun reset() {
        subscriptionCounts.clear()
        stopPolling()
        _mode.tryEmit(MarketMode.WS)
    }

    private fun onWsConnected() {
        stopPolling()
        _mode.tryEmit(MarketMode.WS)
        eventPublisher.publishEvent(MarketModeChanged(mode = MarketMode.WS.name, ts = Instant.now()))
        log.info("MarketDataStream: WS 모드 복귀")
    }

    private fun onWsDisconnected() {
        startPolling()
        _mode.tryEmit(MarketMode.POLLING)
        eventPublisher.publishEvent(MarketModeChanged(mode = MarketMode.POLLING.name, ts = Instant.now()))
        log.warn("MarketDataStream: WS 끊김 → REST 폴링 모드 진입")
    }

    private fun startPolling() {
        val existing = pollingJob.get()
        if (existing != null && existing.isActive) return
        val job = applicationScope.launch {
            while (isActive) {
                subscriptionCounts.keys.forEach { code ->
                    runCatching {
                        val response = restClient.getCurrentPrice(code)
                        val price = response.output.stckPrpr.toIntOrNull()
                        if (price != null && price > 0) {
                            _priceTicks.tryEmit(PriceTick(code, price, Instant.now()))
                        }
                    }.onFailure { log.warn("폴링 실패 stockCode={}", code, it) }
                }
                delay(pollIntervalMillis.milliseconds)
            }
        }
        pollingJob.set(job)
    }

    private fun stopPolling() {
        val job = pollingJob.getAndSet(null) ?: return
        runBlocking { job.cancelAndJoin() }
    }

    enum class MarketMode { WS, POLLING }
}
