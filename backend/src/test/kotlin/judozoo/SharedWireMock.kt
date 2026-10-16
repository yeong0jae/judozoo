package judozoo

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig

object SharedWireMock {
    val server: WireMockServer by lazy {
        WireMockServer(wireMockConfig().dynamicPort()).apply {
            start()
            Runtime.getRuntime().addShutdownHook(Thread { stop() })
        }
    }

    fun reset() = server.resetAll()
}
