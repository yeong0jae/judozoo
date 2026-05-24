package at.backend.platform.kis

import at.backend.platform.kis.client.KisAuthClient
import at.backend.platform.kis.client.response.KisAuthResponse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class KisAccessTokenProviderTest : FunSpec({

    fun provider(client: KisAuthClient, registry: KisTokenRegistry = KisTokenRegistry(), appKey: String = "APP1"):
            KisAccessTokenProvider = KisAccessTokenProvider(appKey, client, registry)

    context("토큰 발급·캐시") {
        test("첫 호출 시 발급, 같은 인스턴스의 두 번째 호출은 캐시된 값") {
            val client = mockk<KisAuthClient>()
            every { client.issueToken() } returns
                    KisAuthResponse(accessToken = "init-token", accessTokenExpired = "2099-12-31 23:59:59")

            val p = provider(client)
            p.token shouldBe "init-token"
            p.token shouldBe "init-token"

            verify(exactly = 1) { client.issueToken() }
        }

        test("발급 중 발생한 예외는 그대로 던진다") {
            val client = mockk<KisAuthClient>()
            every { client.issueToken() } throws RuntimeException("boom")

            val ex = shouldThrow<RuntimeException> { provider(client).token }
            ex.message shouldBe "boom"
        }
    }

    context("동일 appKey 공유 — registry 공유로 두 provider 인스턴스가 같은 토큰") {
        test("같은 appKey, 같은 registry면 토큰 발급 1회만 발생") {
            val client = mockk<KisAuthClient>()
            every { client.issueToken() } returns
                    KisAuthResponse(accessToken = "shared", accessTokenExpired = "2099-12-31 23:59:59")
            val registry = KisTokenRegistry()

            val p1 = provider(client, registry, appKey = "SAME")
            val p2 = provider(client, registry, appKey = "SAME")

            p1.token shouldBe "shared"
            p2.token shouldBe "shared"

            verify(exactly = 1) { client.issueToken() }
        }

        test("다른 appKey면 각자 발급") {
            val client = mockk<KisAuthClient>()
            every { client.issueToken() } returnsMany listOf(
                KisAuthResponse(accessToken = "t1", accessTokenExpired = "2099-12-31 23:59:59"),
                KisAuthResponse(accessToken = "t2", accessTokenExpired = "2099-12-31 23:59:59"),
            )
            val registry = KisTokenRegistry()

            val p1 = provider(client, registry, appKey = "KEY-A")
            val p2 = provider(client, registry, appKey = "KEY-B")

            p1.token shouldBe "t1"
            p2.token shouldBe "t2"

            verify(exactly = 2) { client.issueToken() }
        }
    }
})
