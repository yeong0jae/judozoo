package judozoo

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig

/**
 * 테스트 JVM 전체가 함께 쓰는 WireMock 서버 하나. 테스트마다 [reset]으로 스텁만 비운다.
 *
 * **테스트마다 새 포트로 띄우고 내리면 안 된다.** 반납한 포트를 통합 테스트(Spring·Testcontainers)가 곧바로
 * 잡아, 다음 요청이 엉뚱한 서버의 401·404를 받았다(2026-10-15).
 */
object SharedWireMock {
    val server: WireMockServer by lazy {
        WireMockServer(wireMockConfig().dynamicPort()).apply {
            start()
            Runtime.getRuntime().addShutdownHook(Thread { stop() })
        }
    }

    fun reset() = server.resetAll()
}
