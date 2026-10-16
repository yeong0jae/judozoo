package judozoo.library.web

import io.kotest.matchers.shouldBe
import judozoo.IntegrationTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@IntegrationTest
class ApiExceptionHandlerTest(@Value("\${local.server.port}") private val port: Int) {
    private fun get(path: String): HttpResponse<String> = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI("http://localhost:$port$path")).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

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
