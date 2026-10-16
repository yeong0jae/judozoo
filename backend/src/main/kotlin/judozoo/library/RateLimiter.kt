package judozoo.library

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 공유 토큰 버킷 레이트 리미터.
 *
 * 1. **한 버킷을 공유한다** — 폴러·상세조회·돌파감지가 각자 리미터를 가지면 합산이 브로커 한도를 넘는다.
 * 2. **한도 도달 시 거부가 아니라 대기한다** — 폴러가 죽는 것보다 늦는 게 낫다. 다만 [timeout]을 넘기면 [RateLimitTimeout].
 *
 * **주기를 함수로 주면 시간대마다 속도가 바뀐다.** 키움은 09~10시에만 한도가 낮아진다.
 * 채울 때마다 그 순간의 주기로 계산하므로, 경계를 넘는 즉시 새 속도가 적용된다.
 *
 * **버킷은 프로세스 메모리에 있다.** 같은 앱키로 브로커를 부르는 프로세스가 둘이 되면(인스턴스를 늘리면) 합이 한도를 넘는다 —
 * 그때는 설정값으로 나눠 준다.
 */
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

    // 대부분의 호출은 0초에 가깝다 — 밀리초 버킷이 아래쪽에 촘촘해야 "안 걸림"과 "조금 걸림"이 갈린다.
    // 위쪽은 KIS 타임아웃 30초·키움 20초를 덮는다.
    private val waitTimer = Timer.builder("rate_limiter_wait")
        .description("레이트 리미터에서 허가를 얻기까지 기다린 시간")
        .tag("limiter", name)
        .serviceLevelObjectives(
            *listOf(1L, 10, 50, 100, 250, 500, 1000, 2000, 5000, 10000, 20000, 30000)
                .map(Duration::ofMillis).toTypedArray(),
        )
        .register(registry)

    // **이 카운터가 움직이면 화면에 빈 칸이 뜬다** — 호출자는 예외를 받고 그 자리를 채우지 못한다.
    private val timeouts = Counter.builder("rate_limiter_timeouts")
        .description("timeout 안에 허가를 얻지 못해 포기한 횟수")
        .tag("limiter", name)
        .register(registry)

    // 잠든 동안에도 스레드 슬롯을 쥐고 있어서, 이 값이 곧 "지금 이 리미터 때문에 묶여 있는 스레드"다.
    private val waiting = registry.gauge(
        "rate_limiter_waiting_threads",
        listOf(io.micrometer.core.instrument.Tag.of("limiter", name)),
        AtomicInteger(0),
    )!!

    /**
     * 허가를 얻을 때까지 블로킹한다.
     *
     * 타임아웃은 대기 분포에 넣지 않고 카운터로만 센다 — 허가를 **못 얻은** 시간을 섞으면
     * 성공 대기 분포가 상한(timeout)에 붙어 왜곡된다.
     */
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

    /** 마지막 갱신 이후 경과 시간만큼 토큰을 채운다. 락을 쥔 상태여야 한다. */
    private fun refill() {
        val now = System.nanoTime()
        val elapsed = now - updatedAt
        if (elapsed <= 0) return
        tokens = minOf(capacity, tokens + elapsed * capacity / period().toNanos())
        updatedAt = now
    }
}

/** timeout 안에 허가를 얻지 못했다. */
class RateLimitTimeout(message: String) : RuntimeException(message)
