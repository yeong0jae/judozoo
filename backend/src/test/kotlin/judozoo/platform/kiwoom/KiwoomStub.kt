package judozoo.platform.kiwoom

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.http.Fault
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import judozoo.SharedWireMock
import judozoo.library.brokertoken.TokenSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class KiwoomStub : AutoCloseable {
    val server = SharedWireMock.server.also { SharedWireMock.reset() }

    var now: Instant = Instant.parse("2026-10-15T05:00:00Z")

    val clock: Clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
        override fun instant(): Instant = now
    }

    val client = KiwoomClient(
        KiwoomProperties(baseUrl = server.baseUrl(), queryPermitsPerSecond = 100, peakQueryPermitsPerSecond = 100),
        object : TokenSource {
            override fun get() = "tok"
            override fun invalidate() {}
        },
        clock,
        SimpleMeterRegistry(),
    )

    fun respond(path: String, json: String) {
        server.stubFor(post(urlEqualTo(path)).willReturn(okJson(json)))
    }

    fun fail(path: String) {
        server.stubFor(post(urlEqualTo(path)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)))
    }

    fun calls(path: String) = server.countRequestsMatching(postRequestedFor(urlEqualTo(path)).build()).count

    override fun close() = SharedWireMock.reset()
}
