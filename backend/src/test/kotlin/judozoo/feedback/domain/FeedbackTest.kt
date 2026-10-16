package judozoo.feedback.domain

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class FeedbackTest {
    private val now = LocalDateTime.of(2026, 9, 19, 21, 0)

    @Nested
    inner class 남는_기록 {
        /** 알림 문구가 저장된 값을 그대로 읽으므로 들어갈 때 털어야 한다. */
        @Test
        fun `앞뒤 공백을 털고 남긴다`() {
            val feedback = Feedback.write(7, "  줄 간격이 좁아요\n", now)

            feedback.content shouldBe "줄 간격이 좁아요"
            feedback.userId shouldBe 7
        }

        /** 대체 키 이전 세션에는 내부 식별자가 없다 — 누구인지 몰라도 내용은 잃지 않는다. */
        @Test
        fun `옛 세션이라도 의견은 남는다`() {
            val feedback = Feedback.write(null, "잘 쓰고 있습니다", now)

            feedback.userId.shouldBeNull()
            feedback.content shouldBe "잘 쓰고 있습니다"
        }
    }
}
