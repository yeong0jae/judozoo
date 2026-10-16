package judozoo.leadingstock.application

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import judozoo.leadingstock.domain.MinuteCandle
import judozoo.library.KST
import judozoo.platform.toss.TossCandles
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 당일 분봉 저장소 — 한 번 채운 뒤 마지막 봉 이후만 받아 이어 붙인다. */
class TodayMinutesTest {
    private val today = LocalDate.of(2026, 9, 28)
    private var now = LocalDateTime.of(2026, 9, 28, 10, 0)
    private val responses = HashMap<String, List<MinuteCandle>>()
    private val failing = HashSet<String>()
    private var gate: CountDownLatch? = null
    private val calls = Collections.synchronizedList(mutableListOf<Pair<String, LocalDateTime?>>())

    private val toss = mockk<TossCandles> {
        every { fetchTodayMinuteCandles(any(), any()) } answers {
            val (code, since) = firstArg<String>() to secondArg<LocalDateTime?>()
            gate?.await(1, TimeUnit.SECONDS)
            calls += code to since
            if (code in failing) throw IllegalStateException("토스 오류")
            responses[code].orEmpty().filter { since == null || !it.dateTime.isBefore(since) }
        }
    }
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = KST
        override fun withZone(zone: ZoneId): Clock = fixed(instant(), zone)
        override fun instant(): Instant = now.atZone(KST).toInstant()
    }
    private val minutes = TodayMinutes(toss, clock)

    private fun bar(hour: Int, minute: Int, close: Long = 100, day: LocalDate = today) =
        MinuteCandle(day.atTime(hour, minute), close, close, close, close, 1, close)

    private fun List<MinuteCandle>.minutes() = map { it.dateTime.minute }

    @Nested
    inner class 감시_풀_갱신 {
        @Test
        fun `처음엔 오늘치를 통째로 받는다`() {
            responses["005930"] = listOf(bar(9, 0), bar(9, 1))

            minutes.sync(listOf("005930"))

            calls shouldBe listOf("005930" to null)
        }

        @Test
        fun `두 번째부터는 마지막 봉 조금 앞부터만 받는다`() {
            responses["005930"] = listOf(bar(9, 0), bar(9, 1), bar(9, 2))
            minutes.sync(listOf("005930"))

            minutes.sync(listOf("005930"))

            calls[1] shouldBe ("005930" to today.atTime(9, 0)) // 마지막 09:02에서 2분 앞
        }

        @Test
        fun `새 봉은 뒤에 붙고 진행 중이던 봉은 덮어쓴다`() {
            responses["005930"] = listOf(bar(9, 0), bar(9, 1, close = 100))
            minutes.sync(listOf("005930"))
            responses["005930"] = listOf(bar(9, 0), bar(9, 1, close = 105), bar(9, 2, close = 110))

            minutes.sync(listOf("005930"))

            minutes.ensure("005930", now).map { it.closePrice } shouldBe listOf(100L, 105L, 110L)
        }

        @Test
        fun `한 종목이 실패해도 나머지는 받고 실패 수를 돌려준다`() {
            responses["000660"] = listOf(bar(9, 0))
            failing += "005930"

            minutes.sync(listOf("005930", "000660")) shouldBe 1
            minutes.syncedSince("000660", now) shouldBe true
        }
    }

    @Nested
    inner class 읽을_때_이어_받기 {
        @Test
        fun `기준 이후에 받은 값이면 부르지 않는다`() {
            responses["005930"] = listOf(bar(9, 0))
            minutes.sync(listOf("005930"))

            minutes.ensure("005930", now.minusSeconds(30))

            calls.size shouldBe 1
        }

        @Test
        fun `낡았으면 새 봉만 받는다`() {
            responses["005930"] = listOf(bar(9, 0), bar(9, 5))
            minutes.sync(listOf("005930"))
            now = now.plusMinutes(1)

            minutes.ensure("005930", now.minusSeconds(30))

            calls.last() shouldBe ("005930" to today.atTime(9, 3))
        }

        @Test
        fun `감시 풀 밖의 종목도 처음 읽을 때 통째로 받는다`() {
            responses["035720"] = listOf(bar(9, 0), bar(9, 1))

            minutes.ensure("035720", now).minutes() shouldBe listOf(0, 1)
            calls shouldBe listOf("035720" to null)
        }

        /** 낡은 봉이 없는 봉보다 낫다. */
        @Test
        fun `받다가 실패하면 들고 있던 봉을 준다`() {
            responses["005930"] = listOf(bar(9, 0))
            minutes.sync(listOf("005930"))
            failing += "005930"
            now = now.plusMinutes(1)

            minutes.ensure("005930", now).minutes() shouldBe listOf(0)
            minutes.syncedSince("005930", now) shouldBe false
        }

        @Test
        fun `랭킹 코드와 맨 코드는 같은 종목이다`() {
            responses["005930"] = listOf(bar(9, 0))
            minutes.sync(listOf("005930_AL"))

            minutes.ensure("005930", now)

            calls.size shouldBe 1
        }
    }

    @Nested
    inner class 정리 {
        @Test
        fun `감시 풀에서 빠져도 상한 안에서는 이어 받을 봉을 남긴다`() {
            responses["005930"] = listOf(bar(9, 0))
            minutes.sync(listOf("005930"))
            now = now.plusMinutes(11)

            minutes.sync(emptyList())
            minutes.ensure("005930", now)

            calls.last() shouldBe ("005930" to today.atTime(8, 58))
        }

        @Test
        fun `상한을 넘으면 가장 오래 안 읽힌 종목부터 버린다`() {
            minutes.maxStocks = 1
            minutes.ensure("000660", now)
            now = now.plusSeconds(10)

            minutes.ensure("035720", now)

            minutes.syncedSince("000660", LocalDateTime.MIN) shouldBe false
        }

        @Test
        fun `날짜가 바뀌면 어제 봉을 버리고 새로 받는다`() {
            responses["005930"] = listOf(bar(9, 0))
            minutes.sync(listOf("005930"))
            now = now.plusDays(1)
            responses["005930"] = listOf(bar(9, 0, day = today.plusDays(1)))

            minutes.sync(listOf("005930"))

            calls.last() shouldBe ("005930" to null)
        }
    }

    @Nested
    inner class 동시_요청 {
        /** 감시 풀 밖의 종목을 여럿이 한꺼번에 연 상황. */
        @Test
        fun `같은 종목을 동시에 열면 토스를 한 번만 부른다`() {
            responses["000660"] = listOf(bar(9, 0), bar(9, 1))
            gate = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(4)
            val futures = (1..4).map { pool.submit<List<MinuteCandle>> { minutes.ensure("000660", now) } }
            Thread.sleep(100)
            gate!!.countDown()

            futures.map { it.get(2, TimeUnit.SECONDS).minutes() } shouldBe List(4) { listOf(0, 1) }
            calls.size shouldBe 1
            pool.shutdown()
        }

        @Test
        fun `막 받은 값이 있으면 뒤에 온 요청은 다시 받지 않는다`() {
            responses["000660"] = listOf(bar(9, 0))
            minutes.ensure("000660", now)

            minutes.ensure("000660", now.minusSeconds(30))

            calls.size shouldBe 1
        }
    }
}
