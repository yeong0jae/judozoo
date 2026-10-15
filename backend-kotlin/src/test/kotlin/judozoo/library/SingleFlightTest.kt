package judozoo.library

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SingleFlightTest {
    private val flight = SingleFlight<String, String>()
    private val calls = AtomicInteger()
    private val pool = Executors.newFixedThreadPool(4)

    /** 선두가 [release]를 기다리는 동안 나머지가 붙는다. */
    private fun <T> concurrently(count: Int, release: CountDownLatch, task: () -> T): List<java.util.concurrent.Future<T>> {
        val futures = List(count) { pool.submit<T> { task() } }
        Thread.sleep(100)
        release.countDown()
        return futures
    }

    @Nested
    inner class 동시_호출 {
        @Test
        fun `같은 키면 한 번만 부르고 모두 같은 결과를 받는다`() {
            val release = CountDownLatch(1)

            val results = concurrently(4, release) {
                flight.run("AAPL") { calls.incrementAndGet(); release.await(1, TimeUnit.SECONDS); "봉" }
            }.map { it.get() }

            calls.get() shouldBe 1
            results shouldBe List(4) { "봉" }
        }

        @Test
        fun `선두가 실패하면 기다리던 쪽도 같은 예외를 받는다`() {
            val release = CountDownLatch(1)

            val failures = concurrently(3, release) {
                runCatching {
                    flight.run("AAPL") { calls.incrementAndGet(); release.await(1, TimeUnit.SECONDS); throw IllegalStateException("KIS 오류") }
                }.exceptionOrNull()?.message
            }.map { it.get() }

            calls.get() shouldBe 1
            failures shouldBe List(3) { "KIS 오류" }
        }

        @Test
        fun `키가 다르면 따로 부른다`() {
            flight.run("AAPL") { calls.incrementAndGet(); "a" }
            flight.run("MSFT") { calls.incrementAndGet(); "b" }

            calls.get() shouldBe 2
        }

        @Test
        fun `끝난 뒤에 온 호출은 새로 부른다`() {
            flight.run("AAPL") { calls.incrementAndGet(); "a" }
            flight.run("AAPL") { calls.incrementAndGet(); "a" }

            calls.get() shouldBe 2
        }

        /** 진행 중 표시를 안 지우면 그 키가 영영 막힌다. */
        @Test
        fun `실패한 뒤에는 다시 부를 수 있다`() {
            shouldThrow<IllegalStateException> { flight.run("AAPL") { throw IllegalStateException("첫 시도 실패") } }

            flight.run("AAPL") { "성공" } shouldBe "성공"
        }
    }
}
