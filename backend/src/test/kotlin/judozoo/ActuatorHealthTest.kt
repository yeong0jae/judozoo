package judozoo

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@IntegrationTest
class ActuatorHealthTest(@Value("\${local.server.port}") private val port: Int) {
    @Nested
    inner class 헬스체크 {
        @Test
        fun `DB까지 붙어 있으면 UP으로 답한다`() {
            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI("http://localhost:$port/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString(),
            )

            response.statusCode() shouldBe 200
            response.body() shouldContain "\"db\":{\"status\":\"UP\""
        }
    }
}
