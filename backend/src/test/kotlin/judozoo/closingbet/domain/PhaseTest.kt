package judozoo.closingbet.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.LocalDate

/** 하루 네 칸 — 복기 09:05~15:00 · 베팅 15:00~20:00 · 대기 20:00~08:00 · 결과 08:00~09:05. */
class PhaseTest {
    @Nested
    inner class 하루_네_칸 {
        @ParameterizedTest
        @CsvSource("7,59,NIGHT", "8,0,RESULT", "9,4,RESULT", "9,5,REVIEW", "14,59,REVIEW", "15,0,BET", "19,59,BET", "20,0,NIGHT", "23,30,NIGHT")
        fun `시각이 화면을 정한다`(h: Int, m: Int, moment: Moment) {
            Phase.at(FRI.atTime(h, m), DAYS).moment shouldBe moment
        }

        @Test
        fun `20시 정각부터는 베팅을 받지 않는다`() {
            Phase.at(FRI.atTime(19, 59, 59), DAYS).acceptsBets shouldBe true
            Phase.at(FRI.atTime(20, 0, 0), DAYS).acceptsBets shouldBe false
        }

        @Test
        fun `베팅 시간에는 오늘 판을 받고 어제 판을 복기한다`() {
            val p = Phase.at(FRI.atTime(16, 42), DAYS)

            Triple(p.betting, p.review, p.nextAt) shouldBe Triple(FRI, THU, FRI.atTime(20, 0))
        }

        @Test
        fun `결과 발표는 어제 판을 09시05분까지 공개한다`() {
            val p = Phase.at(FRI.atTime(8, 3), DAYS)

            Triple(p.betting, p.review, p.nextAt) shouldBe Triple(null, THU, FRI.atTime(9, 5))
        }

        @Test
        fun `자정이 지나도 어젯밤 체결된 판을 기다린다`() {
            val p = Phase.at(FRI.atTime(6, 0), DAYS)

            Triple(p.moment, p.betting, p.review) shouldBe Triple(Moment.NIGHT, THU, LocalDate.of(2026, 9, 30))
        }

        @Test
        fun `금요일 밤 판은 월요일 아침에 결과가 나온다`() {
            val p = Phase.at(FRI.atTime(21, 0), DAYS)

            (p.betting to p.nextAt) shouldBe (FRI to MON.atTime(8, 0))
        }

        @Test
        fun `휴장일에는 금요일 판을 기다리며 다음 개장일 아침을 센다`() {
            val p = Phase.at(SAT.atTime(12, 0), DAYS)

            listOf(p.moment, p.betting, p.review, p.nextAt) shouldBe listOf(Moment.NIGHT, FRI, THU, MON.atTime(8, 0))
        }

        @Test
        fun `월요일 새벽은 금요일 판을 기다린다`() {
            val p = Phase.at(MON.atTime(7, 0), DAYS)

            (p.moment to p.betting) shouldBe (Moment.NIGHT to FRI)
        }
    }

    companion object {
        /** 2026-10: 1(목) 2(금) 개장, 3(토)·4(일) 휴장, 5(월) 개장, 6(화) 개장 */
        val DAYS = OpenDays(listOf(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 6)))
        val THU: LocalDate = LocalDate.of(2026, 10, 1)
        val FRI: LocalDate = LocalDate.of(2026, 10, 2)
        val SAT: LocalDate = LocalDate.of(2026, 10, 3)
        val MON: LocalDate = LocalDate.of(2026, 10, 5)
    }
}
