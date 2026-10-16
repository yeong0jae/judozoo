package judozoo.feedback.application

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import judozoo.IntegrationTest
import judozoo.auth.domain.CurrentUser
import judozoo.library.KST
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.yaml.snakeyaml.Yaml
import java.nio.file.Path
import java.time.LocalDateTime

@IntegrationTest
class FeedbackServiceTest @Autowired constructor(
    private val feedback: FeedbackService,
    private val jdbc: JdbcTemplate,
) {
    private val sender = CurrentUser(7, "a@example.com")

    @BeforeEach
    fun 빈_테이블() {
        jdbc.update("DELETE FROM feedback")
    }

    @Suppress("UNCHECKED_CAST")
    private fun alertQuery(): String {
        val file = Path.of("..", "observability", "grafana", "provisioning", "alerting", "rules.yaml")
        val rules = Yaml().load<Map<String, Any>>(file.toFile().readText())
        val group = (rules["groups"] as List<Map<String, Any>>).first { it["name"] == "의견" }
        val rule = (group["rules"] as List<Map<String, Any>>).first()
        val query = (rule["data"] as List<Map<String, Any>>).first { it["refId"] == "QUERY" }
        return (query["model"] as Map<String, Any>)["rawSql"] as String
    }

    @Nested
    inner class 알림_질의 {
        @Test
        fun `방금 들어온 의견을 문구에 필요한 모양으로 돌려준다`() {
            feedback.receive(sender, "차트가\n느려요", LocalDateTime.now(KST))

            val row = jdbc.queryForList(alertQuery()).single()

            row["user"] shouldBe "7"
            row["excerpt"] shouldBe "차트가 느려요"
            (row["value"] as Number).toInt() shouldBe 1
        }

        @Test
        fun `열흘 전 의견은 다시 알리지 않는다`() {
            feedback.receive(sender, "옛날 의견", LocalDateTime.of(2026, 9, 9, 10, 0))

            jdbc.queryForList(alertQuery()).shouldBeEmpty()
        }
    }

    @Nested
    inner class 접수 {
        @Test
        fun `한 건이 남는다`() {
            feedback.receive(sender, "좋아요", LocalDateTime.now(KST))

            jdbc.queryForList("SELECT user_id, content FROM feedback") shouldHaveSize 1
        }
    }
}
