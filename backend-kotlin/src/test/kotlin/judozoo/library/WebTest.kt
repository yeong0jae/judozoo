package judozoo.library

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import judozoo.IntegrationTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@IntegrationTest
class WebTest(@Value("\${local.server.port}") private val port: Int) {
    private fun get(path: String): HttpResponse<String> = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI("http://localhost:$port$path")).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    @Nested
    inner class 헬스체크 {
        @Test
        fun `DB까지 붙어 있으면 UP으로 답한다`() {
            val response = get("/health/db")

            response.statusCode() shouldBe 200
            response.body() shouldContain "\"database\":\"UP\""
        }
    }

    @Nested
    inner class 오류_봉투 {
        @Test
        fun `없는 api 경로는 로그인 전이면 401 봉투로 막는다`() {
            val response = get("/api/nothing-here")

            response.statusCode() shouldBe 401
            response.body() shouldBe """{"code":"UNAUTHORIZED","status":401,"data":null}"""
        }

        @Test
        fun `없는 경로는 404 봉투로 답한다`() {
            val response = get("/nothing-here")

            response.statusCode() shouldBe 404
            response.body() shouldBe """{"code":"NOT_FOUND","status":404,"data":null}"""
        }
    }
}
