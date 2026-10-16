package judozoo.library

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TtlCacheTest {
    private var now = 0L
    private val ticker = { now }
    private fun pass(duration: Duration) {
        now += duration.toNanos()
    }

    private val calls = AtomicInteger()

    @Nested
    inner class 같은_인자로_다시_부르면 {
        @Test
        fun `계산하지 않고 캐시된 값을 준다`() {
            val cache = TtlCache<String, String>("테스트", Duration.ofSeconds(60), ticker = ticker)
            val load = { calls.incrementAndGet(); "결과-005930" }

            cache.get("005930", load) shouldBe "결과-005930"
            cache.get("005930", load) shouldBe "결과-005930"
            calls.get() shouldBe 1
        }

        @Test
        fun `인자가 다르면 따로 계산한다`() {
            val cache = TtlCache<String, String>("테스트", Duration.ofSeconds(60), ticker = ticker)

            cache.get("005930") { calls.incrementAndGet(); "a" }
            cache.get("000660") { calls.incrementAndGet(); "b" }

            calls.get() shouldBe 2
        }
    }

    @Nested
    inner class TTL이_지나면 {
        @Test
        fun `다시 계산한다`() {
            val cache = TtlCache<Unit, Int>("테스트", Duration.ofMillis(100), ticker = ticker)
            val load = { calls.incrementAndGet() }

            cache.get(Unit, load)
            pass(Duration.ofMillis(150))
            cache.get(Unit, load)

            calls.get() shouldBe 2
        }
    }

    @Nested
    inner class 빈_응답은 {
        @Test
        fun `캐시하지 않아 다음 호출에서 다시 시도한다`() {
            val cache = TtlCache<Unit, List<String>>("테스트", Duration.ofSeconds(60), skipIf = { TtlCache.isEmpty(it) }, ticker = ticker)
            val load = { calls.incrementAndGet(); emptyList<String>() }

            cache.get(Unit, load)
            cache.get(Unit, load)

            calls.get() shouldBe 2
        }

        @Test
        fun `값이 채워지면 그때부터는 캐시한다`() {
            val cache = TtlCache<Unit, List<String>>("테스트", Duration.ofSeconds(60), skipIf = { TtlCache.isEmpty(it) }, ticker = ticker)
            val response = mutableListOf<String>()
            val load = { calls.incrementAndGet(); response.toList() }

            cache.get(Unit, load)
            response += "005930"
            cache.get(Unit, load)
            cache.get(Unit, load)

            calls.get() shouldBe 2
        }
    }

    @Nested
    inner class 최대_크기를_넘으면 {
        @Test
        fun `오래된 항목부터 밀려난다`() {
            val cache = TtlCache<String, String>("테스트", Duration.ofSeconds(60), maxSize = 2, ticker = ticker)
            val load = { code: String -> { calls.incrementAndGet(); code } }

            cache.get("A", load("A"))
            cache.get("B", load("B"))
            cache.get("C", load("C"))
            cache.get("A", load("A"))

            calls.get() shouldBe 4
        }
    }

    @Nested
    inner class 만료_순간에_여러_스레드가_동시에_물으면 {
        private val pool = Executors.newFixedThreadPool(4)

        private fun <T> together(count: Int, task: (Int) -> T): List<T> {
            val start = CyclicBarrier(count)
            return (0 until count).map { i -> pool.submit<T> { start.await(); task(i) } }.map { it.get(5, TimeUnit.SECONDS) }
        }

        @Test
        fun `한 번만 계산하고 나머지는 그 결과를 받는다`() {
            val cache = TtlCache<String, String>("테스트", Duration.ofSeconds(60))

            val results = together(4) { cache.get("005930") { calls.incrementAndGet(); Thread.sleep(50); "값:005930" } }

            calls.get() shouldBe 1
            results shouldBe List(4) { "값:005930" }
        }

        @Test
        fun `키가 다르면 따로 계산한다`() {
            val cache = TtlCache<String, String>("테스트", Duration.ofSeconds(60))
            val codes = listOf("000660", "005930", "000660", "005930")

            val results = together(4) { i -> cache.get(codes[i]) { calls.incrementAndGet(); Thread.sleep(50); "값:${codes[i]}" } }

            calls.get() shouldBe 2
            results shouldBe codes.map { "값:$it" }
        }

        @Test
        fun `선두가 실패하면 대기자도 같은 예외를 받는다`() {
            val cache = TtlCache<Unit, String>("테스트", Duration.ofSeconds(60))

            val messages = together(3) {
                shouldThrow<IllegalStateException> {
                    cache.get(Unit) { calls.incrementAndGet(); Thread.sleep(50); throw IllegalStateException("브로커 오류") }
                }.message
            }

            calls.get() shouldBe 1
            messages shouldBe List(3) { "브로커 오류" }
        }

        @Test
        fun `실패한 뒤에는 다시 부를 수 있다`() {
            val cache = TtlCache<Unit, String>("테스트", Duration.ofSeconds(60))
            val load = { if (calls.incrementAndGet() == 1) throw IllegalStateException("첫 시도 실패") else "성공" }

            shouldThrow<IllegalStateException> { cache.get(Unit, load) }

            cache.get(Unit, load) shouldBe "성공"
            calls.get() shouldBe 2
        }
    }

    @Nested
    inner class 미리_갱신하면 {
        @Test
        fun `캐시에 값이 있어도 새로 불러 갈아 끼운다`() {
            val cache = TtlCache<Unit, Int>("테스트", Duration.ofSeconds(60), ticker = ticker)
            val load = { calls.incrementAndGet() }

            cache.get(Unit, load)
            cache.refresh(Unit, load)

            cache.get(Unit, load) shouldBe 2
            calls.get() shouldBe 2
        }

        @Test
        fun `갈아 끼우면 수명이 다시 시작된다`() {
            val cache = TtlCache<Unit, Int>("테스트", Duration.ofMillis(200), ticker = ticker)
            val load = { calls.incrementAndGet() }

            cache.get(Unit, load)
            pass(Duration.ofMillis(150))
            cache.refresh(Unit, load)
            pass(Duration.ofMillis(150))

            cache.get(Unit, load) shouldBe 2
            calls.get() shouldBe 2
        }

        @Test
        fun `실패하면 기존 값을 남긴다`() {
            val cache = TtlCache<Unit, String>("테스트", Duration.ofSeconds(60), ticker = ticker)
            cache.get(Unit) { "기존" }

            shouldThrow<IllegalStateException> { cache.refresh(Unit) { throw IllegalStateException("브로커 오류") } }

            cache.get(Unit) { "새 값" } shouldBe "기존"
        }

        @Test
        fun `갱신이 진행 중이면 비어 있는 캐시를 물은 쪽은 그 결과를 기다린다`() {
            val cache = TtlCache<Unit, String>("테스트", Duration.ofSeconds(60))
            val started = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            val load = { calls.incrementAndGet(); started.countDown(); Thread.sleep(50); "새 값" }

            val refreshing = pool.submit<String> { cache.refresh(Unit, load) }
            started.await()
            val asking = pool.submit<String> { cache.get(Unit, load) }

            refreshing.get() shouldBe "새 값"
            asking.get() shouldBe "새 값"
            calls.get() shouldBe 1
        }
    }

    @Nested
    inner class 수명을_함수로_주면 {
        @Test
        fun `넣을 때마다 그때의 수명을 쓴다`() {
            val lifetimes = listOf(Duration.ofMillis(100), Duration.ofSeconds(60)).iterator()
            val cache = TtlCache<Unit, Int>("테스트", { lifetimes.next() }, ticker = ticker)
            val load = { calls.incrementAndGet() }

            cache.get(Unit, load)
            pass(Duration.ofMillis(150))
            cache.get(Unit, load)
            pass(Duration.ofMillis(150))

            cache.get(Unit, load) shouldBe 2
            calls.get() shouldBe 2
        }

        @Test
        fun `갈아 끼울 때도 수명을 새로 정한다`() {
            val lifetimes = listOf(Duration.ofSeconds(60), Duration.ofMillis(100), Duration.ofSeconds(60)).iterator()
            val cache = TtlCache<Unit, Int>("테스트", { lifetimes.next() }, ticker = ticker)
            val load = { calls.incrementAndGet() }

            cache.get(Unit, load)
            cache.refresh(Unit, load)
            pass(Duration.ofMillis(150))

            cache.get(Unit, load) shouldBe 3
        }
    }

    @Nested
    inner class 폴러가_갱신하는_캐시 {
        @Test
        fun `만료되어도 요청은 마지막 정상값을 받고 폴러만 갱신한다`() {
            val cache = TtlCache<Unit, Int>("테스트", Duration.ZERO, serveStale = true, ticker = ticker)
            val load = { calls.incrementAndGet() }

            cache.get(Unit, load) shouldBe 1
            cache.get(Unit, load) shouldBe 1
            cache.refresh(Unit, load) shouldBe 2
            cache.get(Unit, load) shouldBe 2
            calls.get() shouldBe 2
        }

        @Test
        fun `수명을 함수로 줘도 만료된 뒤 마지막 정상값을 받는다`() {
            val cache = TtlCache<Unit, Int>("테스트", { Duration.ZERO }, serveStale = true, ticker = ticker)
            val load = { calls.incrementAndGet() }

            cache.get(Unit, load) shouldBe 1
            cache.get(Unit, load) shouldBe 1
            cache.refresh(Unit, load) shouldBe 2
            cache.get(Unit, load) shouldBe 2
            calls.get() shouldBe 2
        }

        @Test
        fun `갱신 예외와 빈 응답은 이전 시세를 덮지 않는다`() {
            val cache = TtlCache<Unit, Int?>("테스트", Duration.ZERO, skipIf = { it == null }, serveStale = true, ticker = ticker)

            cache.get(Unit) { 100 } shouldBe 100
            shouldThrow<IllegalStateException> { cache.refresh(Unit) { throw IllegalStateException("외부 장애") } }
            cache.get(Unit) { 999 } shouldBe 100
            cache.refresh(Unit) { null } shouldBe null
            cache.get(Unit) { 999 } shouldBe 100
        }

        @Test
        fun `갱신 중인 폴러를 기다리지 않고 이전값을 받는다`() {
            val cache = TtlCache<Unit, Int>("테스트", Duration.ZERO, serveStale = true)
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            cache.get(Unit) { 1 }

            val pending = Executors.newSingleThreadExecutor().submit<Int> {
                cache.refresh(Unit) { started.countDown(); release.await(2, TimeUnit.SECONDS); 2 }
            }
            started.await(2, TimeUnit.SECONDS)
            try {
                cache.get(Unit) { 999 } shouldBe 1
            } finally {
                release.countDown()
            }

            pending.get() shouldBe 2
            cache.get(Unit) { 999 } shouldBe 2
        }
    }
}
