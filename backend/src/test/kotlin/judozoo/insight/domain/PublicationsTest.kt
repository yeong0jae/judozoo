package judozoo.insight.domain

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class PublicationsTest {
    private data class Row(override val generatedAt: LocalDateTime, override val published: Boolean, override val explained: Boolean) : Publication

    private fun at(h: Int, m: Int) = LocalDateTime.of(2026, 9, 28, h, m)

    @Nested
    inner class 보여줄_사유 {
        @Test
        fun `가장 최근의 설명된 사유를 보여준다`() {
            val rows = listOf(Row(at(8, 30), true, true), Row(at(9, 30), true, true))

            Publications(rows).shown() shouldBe rows[1]
        }

        @Test
        fun `나중에 나온 설명 없음은 앞의 사유를 덮지 않는다`() {
            val rows = listOf(Row(at(9, 30), true, true), Row(at(11, 0), true, false))

            Publications(rows).shown() shouldBe rows[0]
        }

        @Test
        fun `설명된 사유가 없으면 가장 최근의 설명 없음을 쓴다`() {
            val rows = listOf(Row(at(8, 30), true, false), Row(at(9, 30), true, false))

            Publications(rows).shown() shouldBe rows[1]
        }

        @Test
        fun `거절된 답은 보여주지 않는다`() {
            val rows = listOf(Row(at(8, 30), true, true), Row(at(9, 30), false, true))

            Publications(rows).shown() shouldBe rows[0]
        }

        @Test
        fun `게시된 답이 없으면 아무것도 없다`() {
            Publications(listOf(Row(at(8, 30), false, true))).shown().shouldBeNull()
        }
    }
}
