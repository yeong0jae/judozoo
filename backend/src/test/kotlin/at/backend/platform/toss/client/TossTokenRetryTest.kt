package at.backend.platform.toss.client

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException

class TossTokenRetryTest : FunSpec({

    val log = LoggerFactory.getLogger(TossTokenRetryTest::class.java)

    fun 인증클라이언트() = mockk<TossAuthClient>(relaxed = true)

    context("토큰이 유효할 때") {
        test("한 번만 호출하고 캐시를 건드리지 않는다") {
            val auth = 인증클라이언트()
            var 호출횟수 = 0

            val result = auth.withTokenRetry(log) {
                호출횟수++
                "성공"
            }

            result shouldBe "성공"
            호출횟수 shouldBe 1
            verify(exactly = 0) { auth.invalidate() }
        }
    }

    context("다른 인스턴스가 토큰을 새로 발급해 이쪽 토큰이 죽었을 때") {
        test("캐시를 비우고 한 번 더 시도해 스스로 복구한다") {
            val auth = 인증클라이언트()
            var 호출횟수 = 0

            val result = auth.withTokenRetry(log) {
                호출횟수++
                if (호출횟수 == 1) throw 인증실패()
                "재발급 후 성공"
            }

            result shouldBe "재발급 후 성공"
            호출횟수 shouldBe 2
            verify(exactly = 1) { auth.invalidate() }
        }

        test("재시도까지 실패하면 호출측이 알 수 있도록 예외를 올린다") {
            val auth = 인증클라이언트()
            var 호출횟수 = 0

            shouldThrow<HttpClientErrorException.Unauthorized> {
                auth.withTokenRetry(log) {
                    호출횟수++
                    throw 인증실패()
                }
            }

            호출횟수 shouldBe 2
        }
    }

    context("인증과 무관한 실패일 때") {
        test("재시도하지 않고 그대로 올린다") {
            val auth = 인증클라이언트()
            var 호출횟수 = 0

            shouldThrow<HttpServerErrorException> {
                auth.withTokenRetry(log) {
                    호출횟수++
                    throw HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR)
                }
            }

            호출횟수 shouldBe 1
            verify(exactly = 0) { auth.invalidate() }
        }
    }
})

private fun 인증실패() =
    HttpClientErrorException.create(
        HttpStatus.UNAUTHORIZED,
        "Unauthorized",
        org.springframework.http.HttpHeaders(),
        """{"error":{"code":"invalid-token","message":"유효하지 않은 토큰입니다."}}""".toByteArray(),
        null,
    ) as HttpClientErrorException.Unauthorized
