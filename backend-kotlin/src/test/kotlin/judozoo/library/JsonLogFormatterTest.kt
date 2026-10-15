package judozoo.library

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.LoggingEvent
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

class JsonLogFormatterTest {
    private val formatter = JsonLogFormatter(KST)
    private val logger = LoggerContext().getLogger("judozoo.leadingstock")

    private fun event(level: Level, message: String, mdc: Map<String, String> = emptyMap(), error: Throwable? = null) =
        LoggingEvent("fqcn", logger, level, message, error, null).apply {
            instant = Instant.parse("2026-10-15T00:30:00.123Z")
            threadName = "main"
            setMDCPropertyMap(mdc)
        }

    @Suppress("UNCHECKED_CAST")
    private fun parse(line: String) = JsonMapper.builder().build().readValue(line, Map::class.java) as Map<String, Any?>

    @Nested
    inner class Python과_같은_모양 {
        @Test
        fun `기본 필드를 한 줄에 담는다`() {
            val line = formatter.format(event(Level.INFO, "후보 35종목 갱신"))

            line shouldEndWith "}\n"
            parse(line) shouldContainExactly mapOf(
                "ts" to "2026-10-15T09:30:00.123+09:00",
                "level" to "INFO",
                "logger" to "judozoo.leadingstock",
                "msg" to "후보 35종목 갱신",
                "thread" to "main",
            )
        }

        @Test
        fun `경고는 Python 이름으로 적는다`() {
            parse(formatter.format(event(Level.WARN, "x")))["level"] shouldBe "WARNING"
        }

        @Test
        fun `한글을 이스케이프하지 않는다`() {
            formatter.format(event(Level.INFO, "주도주")) shouldContain "주도주"
        }
    }

    @Nested
    inner class 있을_때만_싣는_필드 {
        @Test
        fun `요청 맥락이 있으면 사용자와 경로와 트레이스를 싣는다`() {
            val mdc = mapOf("user" to "7", "request" to "GET /api/x", "traceId" to "abc")

            val parsed = parse(formatter.format(event(Level.INFO, "x", mdc)))

            parsed["user"] shouldBe 7
            parsed["request"] shouldBe "GET /api/x"
            parsed["trace_id"] shouldBe "abc"
        }

        @Test
        fun `스케줄러 로그에는 사용자가 없다`() {
            parse(formatter.format(event(Level.INFO, "x"))).keys shouldNotContain "user"
        }

        @Test
        fun `예외는 스택트레이스를 싣는다`() {
            val parsed = parse(formatter.format(event(Level.ERROR, "실패", error = IllegalStateException("boom"))))

            parsed["exc"].toString() shouldContain "IllegalStateException: boom"
        }
    }
}
