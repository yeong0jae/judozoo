package at.backend.stock.infrastructure

import at.backend.stock.domain.Market
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.springframework.web.client.RestClient
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class KisStockMasterClientTest : FunSpec({

    val ms949: Charset = Charset.forName("MS949")
    val wireMock = WireMockServer(WireMockConfiguration.options().dynamicPort())

    beforeSpec { wireMock.start() }
    afterSpec { wireMock.stop() }
    beforeEach { wireMock.resetAll() }

    // 한 라인 = [9 단축코드][12 표준코드][한글명][part2 고정폭(코스피 228/코스닥 222)]
    fun line(code: String, std: String, name: String, part2Width: Int): String =
        code.padEnd(9) + std + name + "X".repeat(part2Width)

    fun zipBytes(entryName: String, content: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use {
            it.putNextEntry(ZipEntry(entryName))
            it.write(content.toByteArray(ms949))
            it.closeEntry()
        }
        return out.toByteArray()
    }

    fun stubZip(path: String, bytes: ByteArray) {
        wireMock.stubFor(
            WireMock.get(WireMock.urlPathEqualTo(path))
                .willReturn(WireMock.aResponse().withStatus(200).withBody(bytes)),
        )
    }

    fun client(): KisStockMasterClient =
        KisStockMasterClient(
            restClient = RestClient.builder().build(),
            properties = StockMasterProperties(
                kospiUrl = "http://localhost:${wireMock.port()}/kospi.zip",
                kosdaqUrl = "http://localhost:${wireMock.port()}/kosdaq.zip",
            ),
        )

    context("마스터 파일 파싱") {
        test("코스피/코스닥 zip을 코드·이름·시장으로 파싱한다") {
            stubZip(
                "/kospi.zip",
                zipBytes("kospi_code.mst", line("005930", "KR7005930003", "삼성전자", 228)),
            )
            stubZip(
                "/kosdaq.zip",
                zipBytes("kosdaq_code.mst", line("035720", "KR7035720002", "카카오", 222)),
            )

            val stocks = client().fetchAll()

            stocks shouldHaveSize 2
            val samsung = stocks.first { it.shortCode == "005930" }
            samsung.name shouldBe "삼성전자"
            samsung.standardCode shouldBe "KR7005930003"
            samsung.market shouldBe Market.KOSPI
            val kakao = stocks.first { it.shortCode == "035720" }
            kakao.name shouldBe "카카오"
            kakao.market shouldBe Market.KOSDAQ
        }

        test("다운로드가 실패하면 예외를 던진다") {
            wireMock.stubFor(
                WireMock.get(WireMock.urlPathEqualTo("/kospi.zip"))
                    .willReturn(WireMock.aResponse().withStatus(404)),
            )

            shouldThrow<Exception> { client().fetchAll() }
        }
    }
})
