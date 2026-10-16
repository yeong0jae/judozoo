package judozoo.market.application

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import judozoo.library.KST
import judozoo.market.domain.Region
import judozoo.platform.kis.KisHoliday
import judozoo.platform.toss.TossMarketCalendar
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** 미국 장 상태 — 한국 시간으로 도는 서버가 미 동부 기준으로 판정한다. 서머타임이 함정이다. */
class MarketCalendarTest {
    private val kis = mockk<KisHoliday>()
    private val toss = mockk<TossMarketCalendar>()
    private var nanos = 0L

    private fun calendar(kst: LocalDateTime) =
        MarketCalendar(kis, toss, Clock.fixed(kst.atZone(KST).toInstant(), KST)) { nanos }

    /** 휴장 조회는 늘 평일 개장으로 답한다 — 장 시간 판정만 본다. */
    private fun weekday(kst: LocalDateTime) = calendar(kst).also { every { toss.isTradingDay(any(), any()) } returns true }

    @Nested
    inner class 미국_장중_판정 {
        @Test
        fun `서머타임엔 한국 17시에 프리마켓이 열린다`() {
            weekday(LocalDateTime.of(2026, 9, 25, 17, 0)).usMarketStatus() shouldBe (false to true)
        }

        @Test
        fun `정규장이 끝나도 애프터마켓 동안은 열려 있다`() {
            weekday(LocalDateTime.of(2026, 9, 26, 5, 0)).usMarketStatus() shouldBe (false to true) // 미 동부 16:00
        }

        @Test
        fun `서머타임엔 한국 아침 9시에 애프터마켓이 끝난다`() {
            weekday(LocalDateTime.of(2026, 9, 26, 9, 0)).usMarketStatus() shouldBe (false to false) // 미 동부 20:00
        }

        @Test
        fun `표준시엔 한국 17시에 아직 열리지 않는다`() {
            weekday(LocalDateTime.of(2026, 11, 5, 17, 30)).usMarketStatus() shouldBe (false to false)
        }

        @Test
        fun `표준시엔 한국 18시에 열린다`() {
            weekday(LocalDateTime.of(2026, 11, 5, 18, 0)).usMarketStatus() shouldBe (false to true)
        }
    }

    @Nested
    inner class 다음_미국장까지_남은_시간 {
        @Test
        fun `한국 낮이면 그날 저녁 프리마켓까지다`() {
            calendar(LocalDateTime.of(2026, 9, 25, 10, 0)).untilUsSession() shouldBe Duration.ofHours(7)
        }

        @Test
        fun `애프터마켓이 끝난 아침이면 그날 저녁까지다`() {
            calendar(LocalDateTime.of(2026, 9, 26, 9, 30)).untilUsSession() shouldBe Duration.ofMinutes(450)
        }

        /** 2026-11-01 새벽에 서머타임이 끝난다 — 벽시계로 빼면 한 시간이 사라진다. */
        @Test
        fun `서머타임이 끝나는 밤을 넘기면 한 시간 더 기다린다`() {
            // 미 동부 07:00 EDT → 다음 04:00 EST는 한국 11/1 18:00 — 22시간 뒤
            calendar(LocalDateTime.of(2026, 10, 31, 20, 0)).untilUsSession() shouldBe Duration.ofHours(22)
        }
    }

    /** 주말만 건너뛰면 추석 같은 연휴에 장이 안 열린 날을 거래일로 적는다 — 개장일 목록으로 짚는다. */
    @Nested
    inner class 직전_개장일 {
        private val calendar = calendar(LocalDateTime.of(2026, 9, 26, 10, 0))

        @Test
        fun `연휴를 건너뛰어 마지막으로 연 날을 준다`() {
            // 2026 추석 — 9/24(목)~9/25(금) 휴장, 9/23(수)이 마지막 개장일
            every { kis.fetchOpenDays(any()) } returns setOf(LocalDate.of(2026, 9, 22), LocalDate.of(2026, 9, 23), LocalDate.of(2026, 9, 28))

            calendar.previousOpenDay(LocalDate.of(2026, 9, 26)) shouldBe LocalDate.of(2026, 9, 23)
        }

        /** 오늘이 개장일이어도 '직전'은 어제 이전이다 — 오늘을 쓸지는 화면이 시각으로 정한다. */
        @Test
        fun `그날은 넣지 않는다`() {
            every { kis.fetchOpenDays(any()) } returns setOf(LocalDate.of(2026, 9, 22), LocalDate.of(2026, 9, 23))

            calendar.previousOpenDay(LocalDate.of(2026, 9, 23)) shouldBe LocalDate.of(2026, 9, 22)
        }

        /** 빈 목록으로 날짜를 지어내면 틀린 날을 확신 있게 적는다 — 화면이 주말 폴백을 쓰게 둔다. */
        @Test
        fun `목록을 못 받았으면 모른다고 한다`() {
            every { kis.fetchOpenDays(any()) } returns emptySet()

            calendar.previousOpenDay(LocalDate.of(2026, 9, 26)).shouldBeNull()
            calendar.isOpen(LocalDate.of(2026, 9, 26)).shouldBeNull()
        }

        @Test
        fun `목록은 하루 한 번만 받는다`() {
            every { kis.fetchOpenDays(any()) } returns setOf(LocalDate.of(2026, 9, 23))

            calendar.isOpen(LocalDate.of(2026, 9, 23))
            calendar.isOpen(LocalDate.of(2026, 9, 24))

            verify(exactly = 1) { kis.fetchOpenDays(LocalDate.of(2026, 9, 6)) } // 20일 전부터
        }
    }

    /** 토스 장 운영 정보는 초당 3건 — 몰려도 한 번만 묻고, 실패는 오래 들고 있지 않는다. */
    @Nested
    inner class 휴장_조회 {
        private val calendar = calendar(LocalDateTime.of(2026, 9, 24, 10, 0)) // 목요일, 추석
        private val calls = AtomicInteger()
        private var answer: Boolean? = false

        init {
            every { toss.isTradingDay("KR", LocalDate.of(2026, 9, 24)) } answers {
                calls.incrementAndGet()
                Thread.sleep(50) // 뒤따라온 스레드가 진행 중인 조회를 보게 한다
                answer
            }
        }

        @Test
        fun `동시에 몰려도 토스에는 한 번만 묻는다`() {
            val start = CyclicBarrier(5)
            val pool = Executors.newFixedThreadPool(5)

            val results = List(5) { pool.submit<Boolean> { start.await(); calendar.isHoliday(Region.KR) } }.map { it.get() }

            calls.get() shouldBe 1
            results shouldBe List(5) { true }
        }

        @Test
        fun `성공한 답은 그날 내내 다시 묻지 않는다`() {
            calendar.isHoliday(Region.KR)
            calendar.isHoliday(Region.KR)

            calls.get() shouldBe 1
        }

        /** 평일 휴장(추석)을 하루 종일 개장으로 보면 안 된다. */
        @Test
        fun `실패하면 주말로만 판정하고 잠시 뒤 다시 묻는다`() {
            answer = null
            calendar.isHoliday(Region.KR) shouldBe false // 목요일이라 주말 폴백은 개장
            calendar.isHoliday(Region.KR) shouldBe false
            calls.get() shouldBe 1 // 재시도 전에는 다시 묻지 않는다

            nanos += MarketCalendar.RETRY_AFTER_FAILURE.toNanos() + 1
            answer = false

            calendar.isHoliday(Region.KR) shouldBe true
            calls.get() shouldBe 2
        }
    }
}
