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

/**
 * Slack 알림은 Grafana가 규칙 파일의 SQL로 `feedback`을 읽어 만든다.
 *
 * 규칙 파일이 컬럼 이름을 하드코딩하므로, 테이블을 고치면 코드는 멀쩡한 채로 **알림만 조용히 멎는다.**
 * 그 조합을 여기서 한 번 맞춰 본다.
 */
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

    /** 규칙 파일에 적힌 SQL을 그대로 꺼낸다 — 베끼면 같이 낡는다. */
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
            row["excerpt"] shouldBe "차트가 느려요" // 줄바꿈이 남으면 Slack 문구가 줄 단위로 깨진다
            (row["value"] as Number).toInt() shouldBe 1
        }

        /** 창이 10분이다 — 지난 것이 섞여 오면 매분 같은 알림이 울린다. */
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
