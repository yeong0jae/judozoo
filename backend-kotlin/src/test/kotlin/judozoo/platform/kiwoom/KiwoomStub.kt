package judozoo.platform.kiwoom

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.http.Fault
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import judozoo.library.brokertoken.TokenSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** 키움 엔드포인트 테스트용 — WireMock 서버 + 그 서버를 보는 [KiwoomClient]. 시각은 [now]로 옮긴다(기본 KST 2026-10-15 14:00). */
class KiwoomStub : AutoCloseable {
    val server = WireMockServer(wireMockConfig().dynamicPort()).apply { start() }

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

    /** 연결이 끊긴다 — 타임아웃과 같은 갈래(예외)로 떨어진다. */
    fun fail(path: String) {
        server.stubFor(post(urlEqualTo(path)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)))
    }

    fun calls(path: String) = server.countRequestsMatching(postRequestedFor(urlEqualTo(path)).build()).count

    override fun close() = server.stop()
}
