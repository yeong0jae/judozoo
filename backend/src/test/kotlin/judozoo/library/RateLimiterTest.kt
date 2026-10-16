package judozoo.library

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual as intShouldBeGreaterThanOrEqual
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class RateLimiterTest {
    private val registry = SimpleMeterRegistry()

    private fun limiter(
        permits: Int,
        period: Duration = Duration.ofSeconds(1),
        timeout: Duration = Duration.ofSeconds(20),
        name: String = "테스트",
    ) = RateLimiter(name, permits, registry, { period }, timeout)

    private fun elapsedMillis(block: () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return (System.nanoTime() - started) / 1_000_000
    }

    private fun waitedSeconds(name: String) = registry.timer("rate_limiter_wait", "limiter", name).totalTime(TimeUnit.SECONDS)
    private fun gaveUp(name: String) = registry.counter("rate_limiter_timeouts", "limiter", name).count()
    private fun waitingThreads(name: String) = registry.get("rate_limiter_waiting_threads").tag("limiter", name).gauge().value()

    @Nested
    inner class 허용량_안에서 {
        @Test
        fun `한도만큼은 대기 없이 통과한다`() {
            val limiter = limiter(5)

            elapsedMillis { repeat(5) { limiter.acquire() } } shouldBeLessThan 50
        }
    }

    @Nested
    inner class 허용량을_넘으면 {
        @Test
        fun `다음 주기까지 기다렸다가 통과한다`() {
            val limiter = limiter(2, period = Duration.ofMillis(200))
            limiter.acquire()
            limiter.acquire()

            elapsedMillis { limiter.acquire() } shouldBeGreaterThan 50
        }

        @Test
        fun `타임아웃 안에 확보하지 못하면 예외를 던진다`() {
            val limiter = limiter(1, period = Duration.ofSeconds(10), timeout = Duration.ofMillis(100))
            limiter.acquire()

            shouldThrow<RateLimitTimeout> { limiter.acquire() }
        }
    }

    @Nested
    inner class 주기가_시간대마다_바뀌면 {
        @Test
        fun `그 순간의 주기로 허가를 낸다`() {
            var period = Duration.ofMillis(50)
            val limiter = RateLimiter("테스트_가변", 1, registry, { period })

            limiter.acquire()
            val fast = elapsedMillis { limiter.acquire() }

            period = Duration.ofMillis(300)
            limiter.acquire()
            val slow = elapsedMillis { limiter.acquire() }

            fast shouldBeLessThan 100
            slow shouldBeGreaterThan 200
        }
    }

    @Nested
    inner class 잘못된_사용 {
        @Test
        fun `버킷 용량보다 큰 허가는 거부한다`() {
            shouldThrow<IllegalArgumentException> { limiter(3).acquire(permits = 4) }
        }

        @Test
        fun `허용량은 1 이상이어야 한다`() {
            shouldThrow<IllegalArgumentException> { limiter(0) }
        }
    }

    @Nested
    inner class 대기_계측 {
        @Test
        fun `기다린 시간이 대기 분포에 쌓인다`() {
            val limiter = limiter(1, period = Duration.ofMillis(200), name = "계측-대기")
            limiter.acquire()

            limiter.acquire()

            waitedSeconds("계측-대기") shouldBeGreaterThanOrEqual 0.05
        }

        @Test
        fun `한도에 안 걸린 호출도 건수로 남는다`() {
            limiter(5, name = "계측-무대기").acquire()

            registry.timer("rate_limiter_wait", "limiter", "계측-무대기").count() shouldBe 1
        }

        @Test
        fun `포기한 호출은 카운터로만 세고 대기 분포는 건드리지 않는다`() {
            val limiter = limiter(1, Duration.ofSeconds(10), Duration.ofMillis(50), "계측-포기")
            limiter.acquire()
            val waitedBefore = waitedSeconds("계측-포기")

            shouldThrow<RateLimitTimeout> { limiter.acquire() }

            gaveUp("계측-포기") shouldBe 1.0
            waitedSeconds("계측-포기") shouldBe waitedBefore
        }
    }

    @Nested
    inner class 대기_중인_스레드_수 {
        @Test
        fun `줄 선 스레드를 세고 빠져나가면 0으로 돌아온다`() {
            val limiter = limiter(1, Duration.ofMillis(300), Duration.ofSeconds(30), "계측-동시")

            val threads = List(5) { thread { limiter.acquire() } }
            Thread.sleep(150)
            val during = waitingThreads("계측-동시")
            threads.forEach { it.join() }

            during.toInt() intShouldBeGreaterThanOrEqual 2
            waitingThreads("계측-동시") shouldBe 0.0
        }

        @Test
        fun `포기하고 나가도 대기 수가 새지 않는다`() {
            val limiter = limiter(1, Duration.ofSeconds(10), Duration.ofMillis(50), "계측-누수")
            limiter.acquire()

            shouldThrow<RateLimitTimeout> { limiter.acquire() }

            waitingThreads("계측-누수") shouldBe 0.0
        }
    }
}
