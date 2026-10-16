package judozoo.auth.application

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import judozoo.IntegrationTest
import judozoo.auth.domain.AppUserRepository
import judozoo.platform.google.GoogleOAuth.Account
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.io.File
import java.time.LocalDateTime

@IntegrationTest
class AuthServiceTest @Autowired constructor(
    private val auth: AuthService,
    private val users: AppUserRepository,
    private val jdbc: JdbcTemplate,
) {
    private val at = LocalDateTime.of(2026, 10, 16, 10, 0)

    @BeforeEach
    fun 빈_테이블() {
        jdbc.update("DELETE FROM feedback")
        jdbc.update("DELETE FROM app_user")
    }

    private fun signUp(sub: String) = auth.recordLogin(Account(sub, "$sub@example.com"), at)

    private fun opinion(userId: Long, content: String) =
        jdbc.update("INSERT INTO feedback (user_id, content, created_at) VALUES (?, ?, ?)", userId, content, at)

    @Nested
    inner class 첫_로그인 {
        @Test
        fun `내부 식별자를 발급해 돌려준다`() {
            val me = signUp("111")

            me.email shouldBe "111@example.com"
            users.findById(me.id).get().googleSub shouldBe "111"
        }

        @Test
        fun `사람마다 다른 식별자를 받는다`() {
            signUp("111").id shouldNotBe signUp("222").id
        }
    }

    @Nested
    inner class 재방문 {
        @Test
        fun `같은 사람은 식별자가 바뀌지 않고 가입자가 늘지 않는다`() {
            val first = signUp("111")

            auth.recordLogin(Account("111", "111@example.com"), at.plusDays(1)).id shouldBe first.id
            users.count() shouldBe 1
            users.findById(first.id).get().lastLoginAt shouldBe at.plusDays(1)
        }

        @Test
        fun `바뀐 이메일을 따라간다`() {
            signUp("111")

            auth.recordLogin(Account("111", "new@example.com"), at).email shouldBe "new@example.com"
        }
    }

    @Nested
    inner class 탈퇴 {
        @Test
        fun `가입자와 그 사람의 의견을 함께 지우고 다른 사람 것은 남긴다`() {
            val (me, other) = signUp("111") to signUp("222")
            opinion(me.id, "차트가 느려요")
            opinion(other.id, "남의 의견")

            auth.withdraw(me)

            users.findAll().map { it.googleSub } shouldBe listOf("222")
            jdbc.queryForList("SELECT content FROM feedback", String::class.java) shouldBe listOf("남의 의견")
        }

        @Test
        fun `이미 탈퇴했으면 아무 일도 없다`() {
            val me = signUp("111")
            auth.withdraw(me)

            auth.withdraw(me)
        }

        /** Grafana 탈퇴 규칙은 이 로그 한 줄을 센다 — 로거·문구를 바꾸면 알림이 조용히 끊긴다. */
        @Test
        fun `탈퇴 알림이 세는 로그를 남긴다`() {
            val rules = File("../observability/grafana/provisioning/alerting/rules.yaml").readText()
            val expr = rules.substringAfter("name: 탈퇴").substringAfter("expr: ").substringBefore("\n")
            val loggers = Regex("""logger =~ "([^"]+)"""").find(expr)!!.groupValues[1]
            val pattern = Regex("""msg =~ "([^"]+)"""").find(expr)!!.groupValues[1]
            val logger = org.slf4j.LoggerFactory.getLogger(AuthService::class.java) as ch.qos.logback.classic.Logger
            val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
            logger.addAppender(appender)

            auth.withdraw(signUp("111"))

            logger.detachAppender(appender)
            Regex(loggers).matches(AuthService::class.java.name) shouldBe true
            appender.list.any { Regex(pattern).matches(it.formattedMessage) } shouldBe true
        }
    }
}
