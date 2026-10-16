package judozoo.closingbet.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import judozoo.closingbet.domain.PhaseTest.Companion.THU
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.random.Random

class NicknamesTest {
    @Nested
    inner class 닉네임 {
        @ParameterizedTest
        @ValueSource(strings = ["수달", "Brave01", "용감한수달이다1"])
        fun `2자부터 10자까지 한글 영문 숫자`(nick: String) {
            Nicknames.check(nick, emptyList()) shouldBe nick
        }

        @ParameterizedTest
        @ValueSource(strings = ["달", "열한글자닉네임입니다요", "용감한 수달", "수달!", "🦦수달"])
        fun `규칙을 벗어나면 받지 않는다`(nick: String) {
            shouldThrow<NicknameRejected> { Nicknames.check(nick, emptyList()) }
        }

        @Test
        fun `금칙어가 들어 있으면 받지 않는다`() {
            shouldThrow<NicknameRejected> { Nicknames.check("진짜운영자", listOf("운영자")) }.message!! shouldContain "단어"
        }

        @Test
        fun `바꾼 뒤 7일은 다시 못 바꾼다`() {
            val renamed = THU.atTime(10, 0)

            shouldThrow<NicknameRejected> { Nicknames.checkRename(renamed, renamed.plusDays(6).plusHours(23)) }.message!! shouldContain "10/8"
            Nicknames.checkRename(renamed, renamed.plusDays(7))
        }

        @Test
        fun `처음 받은 랜덤 닉네임은 바로 바꿀 수 있다`() {
            Nicknames.checkRename(null, THU.atTime(10, 0))
        }

        @Test
        fun `랜덤 닉네임은 겹치지 않는다`() {
            val rng = Random(1)
            val taken = mutableSetOf<String>()
            repeat(150) {
                val nick = Nicknames.random(taken, rng)
                (nick in taken) shouldBe false
                Nicknames.check(nick, emptyList())
                taken += nick
            }
        }
    }
}
