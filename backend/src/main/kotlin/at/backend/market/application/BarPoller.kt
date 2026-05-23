package at.backend.market.application

import at.backend.market.domain.Bar
import at.backend.trading.application.broker.BrokerTradingClient
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

@Component
class BarPoller(
    private val broker: BrokerTradingClient,
    private val applicationScope: CoroutineScope,
    @Value("\${trading.market.bar-poll-interval-millis}") private val pollIntervalMillis: Long,
) {

    private val log = KotlinLogging.logger {}
    private val subscribed = ConcurrentHashMap.newKeySet<String>()
    private val latestBarEnd = ConcurrentHashMap<String, Instant>()
    private val pollingJob = AtomicReference<Job?>(null)

    private val _bars = MutableSharedFlow<Bar>(extraBufferCapacity = 256)
    val bars: SharedFlow<Bar> = _bars.asSharedFlow()

    @PostConstruct
    fun start() {
        startPolling()
    }

    fun subscribe(stockCode: String) {
        subscribed.add(stockCode)
    }

    fun unsubscribe(stockCode: String) {
        if (subscribed.remove(stockCode)) {
            latestBarEnd.remove(stockCode)
        }
    }

    @PreDestroy
    fun stop() {
        val job = pollingJob.getAndSet(null) ?: return
        runBlocking { job.cancelAndJoin() }
    }

    fun reset() {
        subscribed.clear()
        latestBarEnd.clear()
    }

    /**
     * 폴링 루프. 한 사이클 안에서 1건이라도 실패하면 다음 delay를 ×2로 늘리고(상한 [MAX_BACKOFF_MILLIS]),
     * 전건 성공한 사이클이 나오면 base 주기로 리셋한다. broker 일시 장애에서 호출 폭주를 줄이는 용도.
     */
    private fun startPolling() {
        val baseDelayMillis = pollIntervalMillis
        var currentDelayMillis = baseDelayMillis

        val job = applicationScope.launch {
            while (isActive) {
                var anyFailed = false
                subscribed.forEach { stockCode ->
                    runCatching { pollOnce(stockCode) }
                        .onFailure {
                            anyFailed = true
                            log.warn(it) { "BarPoller 폴링 실패 stockCode=$stockCode" }
                        }
                }
                currentDelayMillis = if (anyFailed) {
                    val next = (currentDelayMillis * 2).coerceAtMost(MAX_BACKOFF_MILLIS)
                    if (next != currentDelayMillis) {
                        log.warn { "BarPoller 백오프 ${currentDelayMillis}ms → ${next}ms" }
                    }
                    next
                } else {
                    if (currentDelayMillis != baseDelayMillis) {
                        log.info { "BarPoller 백오프 해제 → ${baseDelayMillis}ms" }
                    }
                    baseDelayMillis
                }
                delay(currentDelayMillis.milliseconds)
            }
        }
        pollingJob.set(job)
    }

    private fun pollOnce(stockCode: String) {
        // broker가 최신 → 과거 순으로 반환. [0]은 진행 중 봉, [1]은 직전 닫힌 봉을 사용.
        val bar = broker.fetchBars(stockCode).getOrNull(1) ?: return
        val previousEnd = latestBarEnd[stockCode]
        if (previousEnd == null || bar.endTime.isAfter(previousEnd)) {
            latestBarEnd[stockCode] = bar.endTime
            _bars.tryEmit(bar)
        }
    }

    companion object {
        private const val MAX_BACKOFF_MILLIS: Long = 5L * 60 * 1000  // 5분
    }
}
