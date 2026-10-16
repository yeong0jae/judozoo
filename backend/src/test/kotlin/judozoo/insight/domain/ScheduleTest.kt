package judozoo.insight.domain

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import judozoo.insight.domain.Schedule.KR
import judozoo.insight.domain.Schedule.Pick
import judozoo.insight.domain.Schedule.US
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ScheduleTest {
    private val day = LocalDate.of(2026, 9, 28)

    private fun at(h: Int, m: Int, on: LocalDate = day) = on.atTime(h, m)

    private fun made(h: Int, m: Int, published: Boolean = true, explained: Boolean = true, failure: Failure? = null) = Attempt(at(h, m), published, explained, failure)

    private fun failed(h: Int, m: Int, failure: Failure = Failure.TIMEOUT) = made(h, m, published = false, explained = false, failure = failure)

    private fun history(vararg entries: Pair<String, List<Attempt>>) = entries.associate { (code, items) -> code to Attempts(items) }

    @Nested
    inner class 국내_생성_시간 {
        @ParameterizedTest
        @ValueSource(strings = ["08:30", "12:00", "20:00"])
        fun `0830부터 2000까지 만든다`(t: String) {
            KR.inWindow(LocalTime.parse(t)) shouldBe true
        }

        @ParameterizedTest
        @ValueSource(strings = ["08:00", "08:29", "20:01", "23:00"])
        fun `장 초반 30분과 애프터마켓 뒤에는 만들지 않는다`(t: String) {
            KR.inWindow(LocalTime.parse(t)) shouldBe false
        }

        @ParameterizedTest
        @ValueSource(strings = ["08:30", "09:30", "11:00", "14:00", "16:00", "18:00", "20:00"])
        fun `정해진 시각은 일곱 번이다`(t: String) {
            KR.isSlot(LocalTime.parse(t)) shouldBe true
        }

        @Test
        fun `정해진 시각은 5분 폭으로 본다`() {
            KR.isSlot(LocalTime.of(9, 34)) shouldBe true
            KR.isSlot(LocalTime.of(9, 35)) shouldBe false
        }
    }

    @Nested
    inner class 해외_생성_시간 {
        @ParameterizedTest
        @ValueSource(strings = ["08:00", "09:00", "10:00", "11:00", "13:00", "16:30", "18:00"])
        fun `정해진 시각은 뉴욕 기준 일곱 번이다`(t: String) {
            US.isSlot(LocalTime.parse(t)) shouldBe true
            US.inWindow(LocalTime.parse(t)) shouldBe true
        }

        @ParameterizedTest
        @ValueSource(strings = ["04:00", "07:59", "18:01"])
        fun `프리마켓 초반과 1800 뒤에는 만들지 않는다`(t: String) {
            US.inWindow(LocalTime.parse(t)) shouldBe false
        }
    }

    @Nested
    inner class 기사_범위 {
        @Test
        fun `국내는 직전 거래일 1530 이후다`() {
            KR.articlesSince(LocalDate.of(2026, 9, 25)) shouldBe LocalDateTime.of(2026, 9, 25, 15, 30)
        }

        @Test
        fun `해외는 직전 거래일 뉴욕 1600 이후다`() {
            US.articlesSince(LocalDate.of(2026, 9, 24)) shouldBe LocalDateTime.of(2026, 9, 24, 16, 0)
        }
    }

    @Nested
    inner class 보여줄_세션_날짜 {
        private val friday = LocalDate.of(2026, 9, 25)

        @Test
        fun `프리마켓 시작 전에는 직전 거래일을 보여준다`() {
            KR.sessionDay(at(7, 59), true, friday) shouldBe friday
        }

        @Test
        fun `프리마켓이 시작되면 오늘로 넘어간다`() {
            KR.sessionDay(at(8, 0), true, friday) shouldBe day
        }

        @Test
        fun `휴장일에는 하루 종일 직전 거래일에 머문다`() {
            KR.sessionDay(at(12, 0, LocalDate.of(2026, 9, 24)), false, LocalDate.of(2026, 9, 23)) shouldBe LocalDate.of(2026, 9, 23)
        }

        @Test
        fun `해외는 뉴욕 0400에 넘어간다`() {
            US.sessionDay(at(3, 59, friday), true, LocalDate.of(2026, 9, 24)) shouldBe LocalDate.of(2026, 9, 24)
            US.sessionDay(at(4, 0, friday), true, LocalDate.of(2026, 9, 24)) shouldBe friday
        }
    }

    @Nested
    inner class 만들_종목_고르기 {
        @Test
        fun `생성 시간 밖에서는 아무것도 고르지 않는다`() {
            KR.pick(listOf("A"), emptyMap(), at(8, 10)).shouldBeEmpty()
        }

        @Test
        fun `새로 들어온 종목은 정해진 시각이 아니어도 바로 만든다`() {
            KR.pick(listOf("A"), emptyMap(), at(10, 15)) shouldBe listOf(Pick("A", Trigger.ENTRY))
        }

        @Test
        fun `이미 만든 종목은 정해진 시각이 아니면 건드리지 않는다`() {
            KR.pick(listOf("A"), history("A" to listOf(made(10, 16))), at(10, 40)).shouldBeEmpty()
        }

        @Test
        fun `정해진 시각에는 주도주를 다시 만든다`() {
            KR.pick(listOf("A"), history("A" to listOf(made(9, 30))), at(11, 0)) shouldBe listOf(Pick("A", Trigger.SCHEDULED))
        }

        @Test
        fun `정해진 시각이라도 30분 안에 만든 종목은 건너뛴다`() {
            KR.pick(listOf("A", "B"), history("A" to listOf(made(10, 41)), "B" to listOf(made(9, 30))), at(11, 0)) shouldBe listOf(Pick("B", Trigger.SCHEDULED))
        }

        @ParameterizedTest
        @EnumSource(Failure::class, names = ["TIMEOUT", "UNAVAILABLE", "POOL_TIMEOUT"])
        fun `일시 실패하면 1분 뒤 다시 만든다`(failure: Failure) {
            val history = history("A" to listOf(failed(10, 15, failure)))

            KR.pick(listOf("A"), history, at(10, 15)).shouldBeEmpty()
            KR.pick(listOf("A"), history, at(10, 16)) shouldBe listOf(Pick("A", Trigger.RETRY))
        }

        @Test
        fun `다시 실패하면 5분 그다음엔 15분 뒤에 다시 만든다`() {
            val twice = history("A" to listOf(failed(10, 15), failed(10, 16, Failure.UNAVAILABLE)))
            KR.pick(listOf("A"), twice, at(10, 20)).shouldBeEmpty()
            KR.pick(listOf("A"), twice, at(10, 21)) shouldBe listOf(Pick("A", Trigger.RETRY))

            val thrice = history("A" to listOf(failed(10, 15), failed(10, 16), failed(10, 21)))
            KR.pick(listOf("A"), thrice, at(10, 35)).shouldBeEmpty()
            KR.pick(listOf("A"), thrice, at(10, 36)) shouldBe listOf(Pick("A", Trigger.RETRY))
        }

        @Test
        fun `세 번 다시 해도 실패하면 다음 정해진 시각까지 기다린다`() {
            val history = history("A" to listOf(failed(10, 15), failed(10, 16), failed(10, 21), failed(10, 36)))

            KR.pick(listOf("A"), history, at(10, 59)).shouldBeEmpty()
            KR.pick(listOf("A"), history, at(14, 0)) shouldBe listOf(Pick("A", Trigger.SCHEDULED))
        }

        @Test
        fun `성공 뒤의 실패는 처음부터 다시 센다`() {
            val history = history("A" to listOf(failed(9, 0), failed(9, 1), failed(9, 6), made(9, 30), failed(10, 15)))

            KR.pick(listOf("A"), history, at(10, 16)) shouldBe listOf(Pick("A", Trigger.RETRY))
        }

        @ParameterizedTest
        @EnumSource(Failure::class, names = ["CLIENT_ERROR", "BAD_ANSWER"])
        fun `다시 해도 같은 실패는 다시 만들지 않는다`(failure: Failure) {
            KR.pick(listOf("A"), history("A" to listOf(failed(10, 15, failure))), at(10, 30)).shouldBeEmpty()
        }

        @Test
        fun `후보는 새로 들어올 때 바로 만든다`() {
            KR.pick(listOf("A"), emptyMap(), at(10, 15), candidates = listOf("C")) shouldBe listOf(Pick("A", Trigger.ENTRY), Pick("C", Trigger.ENTRY))
        }

        @Test
        fun `사유가 있는 후보는 정해진 시각에 다시 만들지 않는다`() {
            KR.pick(listOf("A"), history("A" to listOf(made(9, 30)), "C" to listOf(made(9, 30))), at(11, 0), listOf("C")) shouldBe listOf(Pick("A", Trigger.SCHEDULED))
        }

        @Test
        fun `설명 없음인 후보는 정해진 시각에 다시 만든다`() {
            KR.pick(emptyList(), history("C" to listOf(made(9, 30, explained = false))), at(11, 0), listOf("C")) shouldBe listOf(Pick("C", Trigger.SCHEDULED))
        }

        @Test
        fun `설명 없음이 나중에 나왔어도 앞서 설명된 후보는 다시 만들지 않는다`() {
            KR.pick(emptyList(), history("C" to listOf(made(9, 30), made(10, 0, explained = false))), at(11, 0), listOf("C")).shouldBeEmpty()
        }

        @Test
        fun `설명 없음인 후보도 정해진 시각이 아니면 기다린다`() {
            KR.pick(emptyList(), history("C" to listOf(made(9, 30, explained = false))), at(10, 40), listOf("C")).shouldBeEmpty()
        }

        @Test
        fun `후보도 Vertex가 아파 실패하면 다시 만든다`() {
            KR.pick(emptyList(), history("C" to listOf(failed(10, 15))), at(10, 16), listOf("C")) shouldBe listOf(Pick("C", Trigger.RETRY))
        }

        @Test
        fun `주도주와 후보에 같은 종목이 있으면 주도주로 본다`() {
            KR.pick(listOf("A"), history("A" to listOf(made(9, 30))), at(11, 0), listOf("A")) shouldBe listOf(Pick("A", Trigger.SCHEDULED))
        }

        @Test
        fun `주도주에서 빠진 종목은 다시 만들지 않는다`() {
            KR.pick(listOf("B"), history("A" to listOf(made(9, 30))), at(11, 0)) shouldBe listOf(Pick("B", Trigger.ENTRY))
        }
    }
}
