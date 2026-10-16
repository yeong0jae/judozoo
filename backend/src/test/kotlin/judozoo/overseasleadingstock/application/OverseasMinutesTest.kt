package judozoo.overseasleadingstock.application

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import judozoo.library.KST
import judozoo.market.application.MarketCalendar
import judozoo.platform.kis.KisOverseasChart
import judozoo.platform.kis.KisOverseasChart.MinutePagesInterrupted
import judozoo.platform.kis.KisOverseasChart.OverseasMinuteCandle
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OverseasMinutesTest {
    private val yesterday = LocalDate.of(2026, 9, 24)
    private val today = LocalDate.of(2026, 9, 25)

    private var now = LocalDateTime.of(2026, 9, 25, 23, 0)
    private var closed = false
    private var error: Exception? = null
    private var response = listOf<OverseasMinuteCandle>()
    private var gate: CountDownLatch? = null
    private val calls = Collections.synchronizedList(mutableListOf<LocalDateTime?>())

    private val archived = sortedMapOf<Triple<String, String, LocalDate>, List<OverseasMinuteCandle>>(compareBy({ it.first }, { it.second }, { it.third }))

    private val chart = mockk<KisOverseasChart> {
        every { fetchMinuteCandles(any(), any(), any()) } answers {
            val since = thirdArg<LocalDateTime?>()
            gate?.await(1, TimeUnit.SECONDS)
            calls += since
            error?.let { throw it }
            response.filter { since == null || !it.dateTime.isBefore(since) }
        }
    }
    private val archive = mockk<OverseasMinuteArchive> {
        every { recent(any(), any(), any()) } answers {
            val (ex, sym, limit) = Triple(firstArg<String>(), secondArg<String>(), thirdArg<Int>())
            synchronized(archived) { archived.filterKeys { it.first == ex && it.second == sym }.entries.reversed().take(limit).map { it.key.third to it.value } }
        }
        every { put(any(), any(), any(), any()) } answers { synchronized(archived) { archived[Triple(firstArg(), secondArg(), thirdArg())] = arg(3) } }
    }
    private val calendar = mockk<MarketCalendar> { every { usMarketStatus() } answers { false to !closed } }
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = KST
        override fun withZone(zone: ZoneId): Clock = fixed(instant(), zone)
        override fun instant(): Instant = now.atZone(KST).toInstant()
    }
    private val minutes = OverseasMinutes(chart, archive, calendar, clock)

    private fun bar(day: LocalDate, kst: LocalDateTime, close: Double = 100.0) = OverseasMinuteCandle(kst, day, close, close, close, close, 1, close)

    private fun open(): List<OverseasMinuteCandle> {
        response = listOf(bar(yesterday, LocalDateTime.of(2026, 9, 25, 4, 59)), bar(today, LocalDateTime.of(2026, 9, 25, 22, 30)))
        return minutes.minuteCandles("NAS", "AAPL")
    }

    @Nested
    inner class 처음_열기 {
        @Test
        fun `2거래일치를 받아 시각순으로 준다`() {
            val bars = open()

            calls shouldBe listOf(null)
            bars.map { it.tradingDay } shouldBe listOf(yesterday, today)
        }

        @Test
        fun `중간에 끊기면 보여만 주고 들고 있지 않는다`() {
            error = MinutePagesInterrupted("끊김", listOf(bar(today, LocalDateTime.of(2026, 9, 25, 22, 30))), RuntimeException())

            minutes.minuteCandles("NAS", "AAPL") shouldHaveSize 1
            error = null
            minutes.minuteCandles("NAS", "AAPL")

            calls shouldBe listOf(null, null)
        }
    }

    @Nested
    inner class 이어_받기 {
        @Test
        fun `30초 안이면 다시 부르지 않는다`() {
            open()
            now = now.plusSeconds(20)

            minutes.minuteCandles("NAS", "AAPL")

            calls shouldHaveSize 1
        }

        @Test
        fun `30초가 지나면 마지막 봉 2분 앞부터만 받는다`() {
            open()
            now = now.plusSeconds(31)

            minutes.minuteCandles("NAS", "AAPL")

            calls.last() shouldBe LocalDateTime.of(2026, 9, 25, 22, 28)
        }

        @Test
        fun `새 봉은 붙고 진행 중이던 봉은 덮어쓴다`() {
            open()
            now = now.plusSeconds(31)
            response = listOf(bar(today, LocalDateTime.of(2026, 9, 25, 22, 30), 105.0), bar(today, LocalDateTime.of(2026, 9, 25, 22, 31), 110.0))

            minutes.minuteCandles("NAS", "AAPL").filter { it.tradingDay == today }.map { it.close } shouldBe listOf(105.0, 110.0)
        }

        @Test
        fun `새 영업일이 보이면 최신이던 날을 직전 거래일로 내린다`() {
            open()
            now = now.plusHours(18)
            val next = LocalDate.of(2026, 9, 26)
            response = listOf(bar(next, LocalDateTime.of(2026, 9, 26, 17, 0)))

            minutes.minuteCandles("NAS", "AAPL").map { it.tradingDay }.toSortedSet().toList() shouldBe listOf(today, next)
        }

        @Test
        fun `이어 받기가 실패하면 들고 있던 봉을 준다`() {
            open()
            now = now.plusSeconds(31)
            error = IllegalStateException("KIS 오류")

            minutes.minuteCandles("NAS", "AAPL") shouldHaveSize 2
        }
    }

    @Nested
    inner class 미국_장이_닫혀_있으면 {
        @Test
        fun `닫힌 뒤에 받은 값은 다시 받지 않는다`() {
            closed = true
            open()
            now = now.plusHours(10)

            minutes.minuteCandles("NAS", "AAPL")

            calls shouldHaveSize 1
        }

        @Test
        fun `장중에 받은 값은 닫힌 뒤 한 번은 다시 받는다`() {
            open()
            closed = true
            now = now.plusMinutes(5)

            minutes.minuteCandles("NAS", "AAPL")
            minutes.minuteCandles("NAS", "AAPL")

            calls shouldHaveSize 2
        }

        @Test
        fun `닫힌 뒤 받았어도 미국 날짜가 달라지면 갱신한다`() {
            closed = true
            open()
            now = now.plusDays(3)

            minutes.minuteCandles("NAS", "AAPL")

            calls shouldHaveSize 2
        }
    }

    @Nested
    inner class 정리 {
        @Test
        fun `10분 넘게 안 봐도 당일 봉을 이어 받는다`() {
            open()
            now = now.plusMinutes(11)
            response = listOf(bar(today, LocalDateTime.of(2026, 9, 25, 22, 30)))
            minutes.minuteCandles("NAS", "MSFT")

            minutes.minuteCandles("NAS", "AAPL")

            calls.last() shouldBe LocalDateTime.of(2026, 9, 25, 22, 28)
        }

        @Test
        fun `상한을 넘으면 오래 안 본 종목은 보관소에서 복원한다`() {
            minutes.maxStocks = 1
            open()
            now = now.plusSeconds(1)
            minutes.minuteCandles("NAS", "MSFT")

            minutes.minuteCandles("NAS", "AAPL")

            calls.last() shouldBe LocalDateTime.of(2026, 9, 25, 5, 0)
        }
    }

    @Nested
    inner class 끝난_날_보관 {
        @Test
        fun `처음 열어 2거래일치를 받으면 직전 거래일을 넘긴다`() {
            open()

            archived.keys.toList() shouldBe listOf(Triple("NAS", "AAPL", yesterday))
        }

        @Test
        fun `날이 넘어가면 최신이던 날을 넘긴다`() {
            open()
            now = now.plusHours(18)
            val next = LocalDate.of(2026, 9, 26)
            response = listOf(bar(today, LocalDateTime.of(2026, 9, 26, 8, 59)), bar(next, LocalDateTime.of(2026, 9, 26, 17, 0)))

            minutes.minuteCandles("NAS", "AAPL")

            archived.getValue(Triple("NAS", "AAPL", today)).map { it.dateTime.hour } shouldBe listOf(22, 8)
        }

        @Test
        fun `마감 확정은 들고 있는 종목의 최신 거래일을 한 번 더 받아 넘긴다`() {
            open()
            response = response + bar(today, LocalDateTime.of(2026, 9, 26, 8, 59), 120.0)

            minutes.settle() shouldBe 0

            calls.last() shouldBe LocalDateTime.of(2026, 9, 25, 22, 28)
            archived.getValue(Triple("NAS", "AAPL", today)).map { it.close } shouldBe listOf(100.0, 120.0)
        }

        @Test
        fun `마감 확정에 실패한 종목은 세고 넘기지 않는다`() {
            open()
            error = IllegalStateException("KIS 오류")

            minutes.settle() shouldBe 1
            archived.containsKey(Triple("NAS", "AAPL", today)) shouldBe false
        }
    }

    @Nested
    inner class 보관소에서_열기 {
        @Test
        fun `직전 거래일이 있으면 그 뒤 봉만 받는다`() {
            archived[Triple("NAS", "AAPL", yesterday)] = listOf(bar(yesterday, LocalDateTime.of(2026, 9, 25, 4, 59)))
            response = listOf(bar(yesterday, LocalDateTime.of(2026, 9, 25, 4, 59)), bar(today, LocalDateTime.of(2026, 9, 25, 22, 30)))

            val bars = minutes.minuteCandles("NAS", "AAPL")

            calls shouldBe listOf(LocalDateTime.of(2026, 9, 25, 5, 0))
            bars.map { it.tradingDay } shouldBe listOf(yesterday, today)
        }

        @Test
        fun `새 봉이 없으면 보관한 두 날을 보여준다`() {
            val dayBefore = LocalDate.of(2026, 9, 23)
            archived[Triple("NAS", "AAPL", dayBefore)] = listOf(bar(dayBefore, LocalDateTime.of(2026, 9, 24, 4, 59)))
            archived[Triple("NAS", "AAPL", yesterday)] = listOf(bar(yesterday, LocalDateTime.of(2026, 9, 25, 4, 59)))

            minutes.minuteCandles("NAS", "AAPL").map { it.tradingDay } shouldBe listOf(dayBefore, yesterday)
        }

        @Test
        fun `보관한 날이 하나뿐이고 새 봉도 없으면 2거래일치를 받는다`() {
            archived[Triple("NAS", "AAPL", yesterday)] = listOf(bar(yesterday, LocalDateTime.of(2026, 9, 25, 4, 59)))

            minutes.minuteCandles("NAS", "AAPL")

            calls shouldBe listOf(LocalDateTime.of(2026, 9, 25, 5, 0), null)
        }

        @Test
        fun `보관한 날이 너무 오래됐으면 2거래일치를 받는다`() {
            val old = LocalDate.of(2026, 9, 18)
            archived[Triple("NAS", "AAPL", old)] = listOf(bar(old, LocalDateTime.of(2026, 9, 19, 4, 59)))

            open()

            calls shouldBe listOf(null)
        }
    }

    @Nested
    inner class 동시_요청 {
        private val pool = Executors.newFixedThreadPool(4)

        @Test
        fun `같은 종목을 동시에 처음 열면 KIS를 한 번만 부른다`() {
            response = listOf(bar(yesterday, LocalDateTime.of(2026, 9, 25, 4, 59)), bar(today, LocalDateTime.of(2026, 9, 25, 22, 30)))
            gate = CountDownLatch(1)

            val futures = List(4) { pool.submit<Int> { minutes.minuteCandles("NAS", "NVDA").size } }
            Thread.sleep(100)
            gate!!.countDown()

            futures.map { it.get() } shouldBe List(4) { 2 }
            calls shouldBe listOf(null)
        }

        @Test
        fun `30초가 지나 동시에 물어도 이어 받기는 한 번이다`() {
            open()
            now = now.plusSeconds(31)
            gate = CountDownLatch(1)

            val futures = List(4) { pool.submit<Int> { minutes.minuteCandles("NAS", "AAPL").size } }
            Thread.sleep(100)
            gate!!.countDown()
            futures.forEach { it.get() }

            calls shouldHaveSize 2
        }
    }

    @Nested
    inner class 거래일_분리 {
        @Test
        fun `한국 자정이 지나도 같은 미국 거래일 봉은 이어 받는다`() {
            open()
            now = LocalDateTime.of(2026, 9, 26, 0, 1)
            response = listOf(bar(today, now, 110.0))

            val result = minutes.minuteCandles("NAS", "AAPL")

            result.filter { it.tradingDay == today }.map { it.close } shouldBe listOf(100.0, 110.0)
            archived.containsKey(Triple("NAS", "AAPL", today)) shouldBe false
        }

        @Test
        fun `거래일 전환 중 조회가 끊기면 이전 날을 확정하지 않고 재시도한다`() {
            open()
            now = now.plusDays(1)
            val next = today.plusDays(1)
            val newBar = bar(next, LocalDateTime.of(2026, 9, 26, 22, 30))
            error = MinutePagesInterrupted("끊김", listOf(newBar), RuntimeException())
            minutes.minuteCandles("NAS", "AAPL")
            archived.containsKey(Triple("NAS", "AAPL", today)) shouldBe false

            error = null
            response = listOf(bar(today, LocalDateTime.of(2026, 9, 26, 8, 59)), newBar)
            val result = minutes.minuteCandles("NAS", "AAPL")

            calls.last() shouldBe LocalDateTime.of(2026, 9, 25, 22, 28)
            result.map { it.tradingDay } shouldBe listOf(today, today, next)
            archived.getValue(Triple("NAS", "AAPL", today)) shouldHaveSize 2
        }

        @Test
        fun `며칠 건너뛰면 실제 직전 거래일을 보관소에서 합친다`() {
            open()
            now = now.plusDays(3)
            val previous = LocalDate.of(2026, 9, 27)
            val latest = LocalDate.of(2026, 9, 28)
            response = listOf(bar(previous, LocalDateTime.of(2026, 9, 27, 23, 0)), bar(latest, LocalDateTime.of(2026, 9, 28, 23, 0)))

            minutes.minuteCandles("NAS", "AAPL").map { it.tradingDay } shouldBe listOf(previous, latest)
            archived.containsKey(Triple("NAS", "AAPL", previous)) shouldBe true
        }

        @Test
        fun `오래 안 본 종목은 최근 두 거래일로 다시 받는다`() {
            open()
            now = now.plusDays(10)
            response = listOf(bar(LocalDate.of(2026, 10, 2), LocalDateTime.of(2026, 10, 2, 23, 0)), bar(LocalDate.of(2026, 10, 5), LocalDateTime.of(2026, 10, 5, 23, 0)))

            val result = minutes.minuteCandles("NAS", "AAPL")

            calls.last().shouldBeNull()
            result.map { it.tradingDay } shouldBe listOf(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 5))
        }
    }

    private fun LocalDateTime.plusSeconds(s: Long) = plus(Duration.ofSeconds(s))
}
