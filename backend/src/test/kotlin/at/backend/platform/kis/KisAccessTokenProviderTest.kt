package at.backend.platform.kis

import at.backend.platform.kis.client.KisAuthClient
import at.backend.platform.kis.client.response.KisAuthResponse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

class KisAccessTokenProviderTest : FunSpec({

    context("초기 토큰 발급") {
        test("생성 시점에 발급한 토큰을 캐시") {
            val client = mockk<KisAuthClient>()
            every { client.issueToken() } returns
                    KisAuthResponse(accessToken = "init-token", accessTokenExpired = "2099-12-31 23:59:59")

            val provider = KisAccessTokenProvider(client)

            provider.token shouldBe "init-token"
        }

        test("발급 중 발생한 예외는 그대로 던진다") {
            val client = mockk<KisAuthClient>()
            every { client.issueToken() } throws RuntimeException("boom")

            val ex = shouldThrow<RuntimeException> { KisAccessTokenProvider(client).token }
            ex.message shouldBe "boom"
        }
    }
})
