package at.backend.market.infrastructure

import at.backend.library.time.atKstInstant
import at.backend.platform.kis.client.KisRestClient
import at.backend.platform.kis.client.response.KisBarResponse
import at.backend.market.domain.Bar
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Component
class BarCache(
    private val restClient: KisRestClient,
    private val applicationScope: CoroutineScope,
    @Value("\${trading.market.bar-poll-interval-millis:30000}") private val pollIntervalMillis: Long,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val subscriptionCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val latestBarEnd = ConcurrentHashMap<String, java.time.Instant>()
    private val pollingJob = AtomicReference<Job?>(null)

    private val _bars = MutableSharedFlow<Bar>(extraBufferCapacity = 256)
    val bars: SharedFlow<Bar> = _bars.asSharedFlow()

    @PostConstruct
    fun start() {
        startPolling()
    }

    fun subscribe(stockCode: String) {
        subscriptionCounts.computeIfAbsent(stockCode) { AtomicInteger(0) }.incrementAndGet()
    }

    fun unsubscribe(stockCode: String) {
        val count = subscriptionCounts[stockCode] ?: return
        if (count.decrementAndGet() <= 0) {
            subscriptionCounts.remove(stockCode)
            latestBarEnd.remove(stockCode)
        }
    }

    fun stop() {
        val job = pollingJob.getAndSet(null) ?: return
        runBlocking { job.cancelAndJoin() }
    }

    fun reset() {
        subscriptionCounts.clear()
        latestBarEnd.clear()
    }

    private fun startPolling() {
        val job = applicationScope.launch {
            while (isActive) {
                subscriptionCounts.keys.forEach { code ->
                    runCatching { pollOnce(code) }
                        .onFailure { log.warn("BarCache 폴링 실패 stockCode={}", code, it) }
                }
                delay(pollIntervalMillis)
            }
        }
        pollingJob.set(job)
    }

    private fun pollOnce(stockCode: String) {
        val response = restClient.getBars(stockCode)
        val bar = response.output2.firstOrNull()?.let { toBar(stockCode, it) } ?: return
        val previousEnd = latestBarEnd[stockCode]
        if (previousEnd == null || bar.endTime.isAfter(previousEnd)) {
            latestBarEnd[stockCode] = bar.endTime
            _bars.tryEmit(bar)
        }
    }

    private fun toBar(stockCode: String, output: KisBarResponse.Output): Bar? {
        val date = runCatching { LocalDate.parse(output.stckBsopDate, DATE_FMT) }.getOrNull() ?: return null
        val time = runCatching { LocalTime.parse(output.stckCntgHour, TIME_FMT) }.getOrNull() ?: return null
        val openPrice = output.stckOprc.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val closePrice = output.stckPrpr.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val endTime = date.atKstInstant(time)
        val startTime = endTime.minus(BAR_DURATION)
        return Bar(
            stockCode = stockCode,
            openPrice = openPrice,
            closePrice = closePrice,
            startTime = startTime,
            endTime = endTime,
        )
    }

    companion object {
        private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
        private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmmss")
        private val BAR_DURATION: Duration = Duration.ofMinutes(3)
    }
}
