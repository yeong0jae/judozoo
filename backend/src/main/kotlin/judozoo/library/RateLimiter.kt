package judozoo.library

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class RateLimiter(
    val name: String,
    permitsPerPeriod: Int,
    registry: MeterRegistry,
    private val period: () -> Duration = { Duration.ofSeconds(1) },
    private val timeout: Duration = Duration.ofSeconds(20),
) {
    init {
        require(permitsPerPeriod > 0) { "permitsPerPeriod는 1 이상이어야 한다" }
    }

    private val capacity = permitsPerPeriod.toDouble()
    private val lock = ReentrantLock()
    private var tokens = capacity
    private var updatedAt = System.nanoTime()

    private val waitTimer = Timer.builder("rate_limiter_wait")
        .description("레이트 리미터에서 허가를 얻기까지 기다린 시간")
        .tag("limiter", name)
        .serviceLevelObjectives(
            *listOf(1L, 10, 50, 100, 250, 500, 1000, 2000, 5000, 10000, 20000, 30000)
                .map(Duration::ofMillis).toTypedArray(),
        )
        .register(registry)

    private val timeouts = Counter.builder("rate_limiter_timeouts")
        .description("timeout 안에 허가를 얻지 못해 포기한 횟수")
        .tag("limiter", name)
        .register(registry)

    private val waiting = registry.gauge(
        "rate_limiter_waiting_threads",
        listOf(io.micrometer.core.instrument.Tag.of("limiter", name)),
        AtomicInteger(0),
    )!!

    fun acquire(permits: Int = 1) {
        require(permits > 0) { "permits는 1 이상이어야 한다" }
        require(permits <= capacity) { "permits($permits)가 버킷 용량($capacity)보다 크다" }

        val started = System.nanoTime()
        val deadline = started + timeout.toNanos()
        waiting.incrementAndGet()
        try {
            while (true) {
                val waitNanos = lock.withLock {
                    refill()
                    if (tokens >= permits) {
                        tokens -= permits
                        waitTimer.record(Duration.ofNanos(System.nanoTime() - started))
                        return
                    }
                    ((permits - tokens) * period().toNanos() / capacity).toLong()
                }
                if (System.nanoTime() + waitNanos > deadline) {
                    timeouts.increment()
                    throw RateLimitTimeout("[$name] ${timeout.toMillis() / 1000.0}초 안에 허가 ${permits}건을 얻지 못했다")
                }
                Thread.sleep(Duration.ofNanos(waitNanos))
            }
        } finally {
            waiting.decrementAndGet()
        }
    }

    private fun refill() {
        val now = System.nanoTime()
        val elapsed = now - updatedAt
        if (elapsed <= 0) return
        tokens = minOf(capacity, tokens + elapsed * capacity / period().toNanos())
        updatedAt = now
    }
}

class RateLimitTimeout(message: String) : RuntimeException(message)
