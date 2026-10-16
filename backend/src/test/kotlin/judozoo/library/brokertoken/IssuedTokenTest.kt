package judozoo.library.brokertoken

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import judozoo.IntegrationTest
import judozoo.library.BrokerTokenUnavailable
import judozoo.library.brokertoken.IssuedToken.Issued
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

@IntegrationTest
class IssuedTokenTest @Autowired constructor(
    private val repository: BrokerTokenRepository,
    private val transaction: TransactionTemplate,
    private val jdbc: JdbcTemplate,
) {
    private val start = Instant.parse("2026-10-15T00:00:00Z")
    private val clock = MovableClock(start)
    private var issued = 0
    private var failure: Exception? = null

    private fun token(provider: String = "KIWOOM") = IssuedToken(provider, repository, transaction, clock) {
        failure?.let { throw it }
        issued++
        Issued("tok-$issued", clock.now + Duration.ofHours(23))
    }

    @BeforeEach
    fun 빈_테이블() {
        jdbc.update("DELETE FROM broker_token")
    }

    private fun stored(accessToken: String, expiresAt: Instant) {
        val naiveUtc = LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC)
        jdbc.update(
            "INSERT INTO broker_token (provider, access_token, expires_at, created_at, updated_at) VALUES ('KIWOOM', ?, ?, ?, ?)",
            accessToken, naiveUtc, naiveUtc, naiveUtc,
        )
    }

    @Nested
    inner class 액세스_토큰 {
        @Test
        fun `DB에 살아 있는 토큰이 있으면 발급하지 않고 쓴다`() {
            stored("tok-db", start + Duration.ofHours(10))

            token().get() shouldBe "tok-db"
            issued shouldBe 0
        }

        @Test
        fun `없으면 발급받아 DB에 남긴다`() {
            token().get() shouldBe "tok-1"

            repository.findByProvider("KIWOOM")!!.accessToken shouldBe "tok-1"
        }

        @Test
        fun `두 번째 호출은 캐시를 쓴다`() {
            val t = token()
            t.get()
            t.get()

            issued shouldBe 1
        }

        @Test
        fun `재기동해도 남긴 토큰을 다시 쓴다`() {
            token().get()

            token().get() shouldBe "tok-1"
            issued shouldBe 1
        }

        @Test
        fun `만료되면 새로 발급해 같은 줄을 덮어쓴다`() {
            val t = token()
            t.get()
            clock.now = start + Duration.ofHours(23)

            t.get() shouldBe "tok-2"
            jdbc.queryForObject("SELECT COUNT(*) FROM broker_token", Int::class.java) shouldBe 1
            repository.findByProvider("KIWOOM")!!.accessToken shouldBe "tok-2"
        }

        @Test
        fun `무효화하면 DB에서도 지우고 다시 발급받는다`() {
            val t = token()
            t.get()

            t.invalidate()
            repository.findByProvider("KIWOOM").shouldBeNull()

            t.get() shouldBe "tok-2"
        }
    }

    @Nested
    inner class 토큰_발급_백오프 {
        @Test
        fun `거부되면 1분 동안은 브로커를 두드리지 않는다`() {
            val t = token()
            failure = IllegalStateException("Kiwoom token 발급 실패 status=302")

            shouldThrow<IllegalStateException> { t.get() }
            failure = null
            shouldThrow<BrokerTokenUnavailable> { t.get() }.message!! shouldContain "백오프"

            issued shouldBe 0
        }

        @Test
        fun `1분이 지나면 다시 발급한다`() {
            val t = token()
            failure = IllegalStateException("거부")
            shouldThrow<IllegalStateException> { t.get() }
            failure = null
            clock.now = start + Duration.ofSeconds(60)

            t.get() shouldBe "tok-1"
        }
    }

    private class MovableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }
}
