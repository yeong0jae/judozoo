package at.backend.market.application

import at.backend.library.time.atKstInstant
import at.backend.market.domain.Bar
import at.backend.platform.kis.client.KisRestClient
import at.backend.platform.kis.client.response.KisBarResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

@Component
class BarPoller(
    private val kisRestClient: KisRestClient,
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
     * 전건 성공한 사이클이 나오면 base 주기로 리셋한다. KIS 일시 장애에서 호출 폭주를 줄이는 용도.
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
        val response = kisRestClient.getBars(stockCode)
        // output2[0]은 진행 중 봉(종가가 계속 움직임). 정밀 비교를 위해 직전 닫힌 봉인 [1] 사용.
        val bar = response.output2.getOrNull(1)?.let { toBar(stockCode, it) } ?: return
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
        private const val MAX_BACKOFF_MILLIS: Long = 5L * 60 * 1000  // 5분
    }
}
