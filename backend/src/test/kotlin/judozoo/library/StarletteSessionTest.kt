package judozoo.library

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant

class StarletteSessionTest {
    private val session = StarletteSession("test-secret", JsonMapper.builder().build())
    private val signedAt = Instant.ofEpochSecond(1_792_070_321)

    private val withUser =
        "eyJ1c2VyIjogeyJpZCI6IDcsICJlbWFpbCI6ICJhQGV4YW1wbGUuY29tIn0sICJfc3RhdGVfZ29vZ2xlX3giOiB7ImRhdGEiOiAxfX0=" +
            ".atDSsQ._chmEutnIautJuu1n_0keYIpTb0"
    private val withoutUser = "eyJfc3RhdGUiOiAieCJ9.atDSsQ.yOvzAT5aZERcEKLJ3qbyumDyEdg"

    @Nested
    inner class Python이_발급한_쿠키 {
        @Test
        fun `세션에 담긴 값을 그대로 읽는다`() {
            val read = session.read(withUser, signedAt.plusSeconds(60))!!

            read["user"] shouldBe mapOf("id" to 7, "email" to "a@example.com")
        }

        @Test
        fun `로그인 전 쿠키도 읽힌다`() {
            session.read(withoutUser, signedAt)!!["user"].shouldBeNull()
        }
    }

    @Nested
    inner class 믿을_수_없는_쿠키 {
        @Test
        fun `내용을 고치면 서명이 맞지 않는다`() {
            val tampered = "eyJ1c2VyIjogeyJpZCI6IDh9fQ==" + withUser.substring(withUser.indexOf('.'))

            session.read(tampered, signedAt).shouldBeNull()
        }

        @Test
        fun `다른 키로 서명한 쿠키는 버린다`() {
            StarletteSession("other-secret", JsonMapper.builder().build()).read(withUser, signedAt).shouldBeNull()
        }

        @Test
        fun `14일이 지나면 만료된다`() {
            session.read(withUser, signedAt + Duration.ofDays(14)).shouldBe(session.read(withUser, signedAt))
            session.read(withUser, signedAt + Duration.ofDays(14).plusSeconds(1)).shouldBeNull()
        }

        @Test
        fun `서명 시각이 미래면 버린다`() {
            session.read(withUser, signedAt.minusSeconds(1)).shouldBeNull()
        }

        @Test
        fun `모양이 다른 값은 버린다`() {
            session.read("garbage", signedAt).shouldBeNull()
            session.read("", signedAt).shouldBeNull()
        }
    }

    @Nested
    inner class Kotlin이_발급한_쿠키 {
        private val data = mapOf("user" to mapOf("id" to 7, "email" to "a@example.com"))

        @Test
        fun `Starlette과 같은 서명을 만든다`() {
            session.write(data, signedAt) shouldBe "eyJ1c2VyIjp7ImlkIjo3LCJlbWFpbCI6ImFAZXhhbXBsZS5jb20ifX0=.atDSsQ.5A_KdLJTTgzmu5rIjQ_gHTZzalU"
        }

        @Test
        fun `쓴 쿠키를 다시 읽는다`() {
            session.read(session.write(data, signedAt), signedAt.plusSeconds(1))!!["user"] shouldBe mapOf("id" to 7, "email" to "a@example.com")
        }

        @Test
        fun `14일이 지나면 읽지 않는다`() {
            session.read(session.write(data, signedAt), signedAt + Duration.ofDays(14).plusSeconds(1)).shouldBeNull()
        }
    }
}
