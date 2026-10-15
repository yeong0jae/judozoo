package judozoo.library.brokertoken

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import judozoo.IntegrationTest
import judozoo.library.BrokerTokenUnavailable
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

@IntegrationTest
class BorrowedTokenTest @Autowired constructor(
    private val repository: BrokerTokenRepository,
    private val jdbc: JdbcTemplate,
) {
    private val start = Instant.parse("2026-10-15T00:00:00Z")
    private val clock = MovableClock(start)
    private val token = BorrowedToken("KIWOOM", repository, clock)

    @BeforeEach
    fun 빈_테이블() {
        jdbc.update("DELETE FROM broker_token")
    }

    /** Python `token_store.save`가 하는 일. */
    private fun pythonSaves(accessToken: String, expiresAt: Instant) {
        val naiveUtc = LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC)
        jdbc.update("DELETE FROM broker_token WHERE provider = 'KIWOOM'")
        jdbc.update(
            "INSERT INTO broker_token (provider, access_token, expires_at, created_at, updated_at) VALUES ('KIWOOM', ?, ?, ?, ?)",
            accessToken, naiveUtc, naiveUtc, naiveUtc,
        )
    }

    @Nested
    inner class Python이_발급한_토큰 {
        @Test
        fun `DB에 남긴 토큰을 빌려 쓴다`() {
            pythonSaves("tok-1", start + Duration.ofHours(23))

            token.get() shouldBe "tok-1"
        }

        @Test
        fun `살아 있는 동안은 DB를 다시 읽지 않는다`() {
            pythonSaves("tok-1", start + Duration.ofHours(23))
            token.get()
            pythonSaves("tok-2", start + Duration.ofHours(23))

            token.get() shouldBe "tok-1"
        }

        @Test
        fun `만료되면 Python이 새로 남긴 토큰을 읽는다`() {
            pythonSaves("tok-1", start + Duration.ofHours(23))
            token.get()
            pythonSaves("tok-2", start + Duration.ofHours(46))

            clock.now = start + Duration.ofHours(23)

            token.get() shouldBe "tok-2"
        }
    }

    @Nested
    inner class 발급하지_않는다 {
        @Test
        fun `토큰이 없으면 기다리라고 답한다`() {
            shouldThrow<BrokerTokenUnavailable> { token.get() }
        }

        @Test
        fun `만료된 토큰만 있으면 기다리라고 답한다`() {
            pythonSaves("tok-1", start - Duration.ofSeconds(1))

            shouldThrow<BrokerTokenUnavailable> { token.get() }
        }
    }

    @Nested
    inner class 브로커가_토큰을_거부하면 {
        @Test
        fun `DB에 같은 토큰이 남아 있어도 다시 쓰지 않는다`() {
            pythonSaves("tok-1", start + Duration.ofHours(23))
            token.get()

            token.invalidate()

            shouldThrow<BrokerTokenUnavailable> { token.get() }
        }

        @Test
        fun `Python이 새 토큰을 남기면 그걸 쓴다`() {
            pythonSaves("tok-1", start + Duration.ofHours(23))
            token.get()
            token.invalidate()

            pythonSaves("tok-2", start + Duration.ofHours(23))

            token.get() shouldBe "tok-2"
        }

        @Test
        fun `DB는 건드리지 않는다`() {
            pythonSaves("tok-1", start + Duration.ofHours(23))
            token.get()

            token.invalidate()

            repository.findByProvider("KIWOOM")!!.accessToken shouldBe "tok-1"
        }
    }

    private class MovableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }
}
