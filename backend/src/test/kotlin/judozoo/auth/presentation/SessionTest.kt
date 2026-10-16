package judozoo.auth.presentation

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import judozoo.auth.domain.CurrentUser
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SessionTest {
    @Nested
    inner class 세션_복원 {
        @Test
        fun `담아둔 값에서 사용자를 되살린다`() {
            Session(mapOf("user" to mapOf("id" to 7, "email" to "a@example.com"))).user shouldBe CurrentUser(7, "a@example.com")
        }

        @Test
        fun `세션이 비어 있으면 사용자가 없다`() {
            Session().user.shouldBeNull()
        }

        @Test
        fun `모양이 다른 값은 사용자로 치지 않는다`() {
            Session(mapOf("user" to "user")).user.shouldBeNull()
            Session(mapOf("user" to mapOf("id" to 7))).user.shouldBeNull()
        }

        @Test
        fun `식별자가 숫자가 아니면 사용자로 치지 않는다`() {
            Session(mapOf("user" to mapOf("id" to true, "email" to "a@example.com"))).user.shouldBeNull()
            Session(mapOf("user" to mapOf("id" to "7", "email" to "a@example.com"))).user.shouldBeNull()
        }

        @Test
        fun `내부 식별자가 없는 옛 세션은 로그인이 풀린다`() {
            Session(mapOf("user" to mapOf("sub" to "google-123", "email" to "a@example.com"))).user.shouldBeNull()
        }
    }
}
