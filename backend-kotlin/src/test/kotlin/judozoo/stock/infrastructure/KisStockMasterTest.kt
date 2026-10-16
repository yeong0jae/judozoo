package judozoo.stock.infrastructure

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import judozoo.SharedWireMock
import judozoo.stock.domain.Market
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** KIS 종목 마스터 파일 파싱 — 고정폭(국내) / 탭 구분(해외), 인코딩 MS949. 파싱 실패가 조용히 빈 카탈로그로 이어지므로 포맷 함정을 여기서 잡는다. */
class KisStockMasterTest {
    private val server = SharedWireMock.server.also { SharedWireMock.reset() }
    private val master = KisStockMaster(server.baseUrl())
    private val at = LocalDateTime.of(2026, 9, 12, 7, 40)

    @AfterEach
    fun 정리() = SharedWireMock.reset()

    /** MS949로 인코딩해 zip 한 개 엔트리로 만든다 — 실제 CDN 응답과 같은 모양. */
    private fun zipped(text: String): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("master.mst"))
            z.write(text.toByteArray(Charset.forName("MS949")))
            z.closeEntry()
        }
    }.toByteArray()

    private fun serve(file: String, text: String) {
        server.stubFor(get(urlPathEqualTo("/$file")).willReturn(aResponse().withBody(zipped(text))))
    }

    /** part1(단축코드 9 + 표준코드 12 + 한글명) + 고정폭 part2. */
    private fun domestic(shortCode: String, standardCode: String, name: String, part2: Int) = shortCode.padEnd(9) + standardCode.padEnd(12) + name + "X".repeat(part2)

    private fun overseas(exchange: String, symbol: String, korean: String, english: String) = listOf("", "", exchange, "", symbol, "", korean, english, "extra").joinToString("\t")

    @Nested
    inner class 국내_마스터 {
        @Test
        fun `고정폭 라인에서 코드와 종목명을 뽑는다`() {
            serve("kospi_code.mst.zip", domestic("005930", "KR7005930003", "삼성전자", KOSPI))
            serve("kosdaq_code.mst.zip", "")

            val s = master.fetchDomestic(at).single()

            listOf(s.shortCode, s.standardCode, s.name, s.market) shouldBe listOf("005930", "KR7005930003", "삼성전자", Market.KOSPI)
        }

        /** 폭을 잘못 쓰면 종목명 끝에 part2 쓰레기가 붙는다. */
        @Test
        fun `코스피와 코스닥은 part2 폭이 달라 각자 잘린다`() {
            serve("kospi_code.mst.zip", domestic("005930", "KR7005930003", "삼성전자", KOSPI))
            serve("kosdaq_code.mst.zip", domestic("035720", "KR7035720002", "카카오", KOSDAQ))

            master.fetchDomestic(at).associate { it.shortCode to (it.name to it.market) } shouldBe
                mapOf("005930" to ("삼성전자" to Market.KOSPI), "035720" to ("카카오" to Market.KOSDAQ))
        }

        /** MS949로 읽어야 한다 — UTF-8로 읽으면 종목명이 깨진다. */
        @Test
        fun `한글이 깨지지 않는다`() {
            serve("kospi_code.mst.zip", domestic("068270", "KR7068270008", "셀트리온", KOSPI))
            serve("kosdaq_code.mst.zip", "")

            master.fetchDomestic(at)[0].name shouldBe "셀트리온"
        }

        /** 파일 끝 개행·머리말 등 part2 폭에 못 미치는 줄이 섞여 들어온다. */
        @Test
        fun `길이가 모자란 줄은 건너뛴다`() {
            serve("kospi_code.mst.zip", "짧은줄\n${domestic("005930", "KR7005930003", "삼성전자", KOSPI)}\n")
            serve("kosdaq_code.mst.zip", "")

            master.fetchDomestic(at).map { it.shortCode } shouldBe listOf("005930")
        }

        @Test
        fun `단축코드가 빈 줄은 건너뛴다`() {
            serve("kospi_code.mst.zip", domestic("", "KR7005930003", "이름만있음", KOSPI))
            serve("kosdaq_code.mst.zip", "")

            master.fetchDomestic(at).shouldBeEmpty()
        }
    }

    @Nested
    inner class 해외_마스터 {
        private fun exchanges(nas: String = "", nys: String = "", ams: String = "") {
            serve("nasmst.cod.zip", nas)
            serve("nysmst.cod.zip", nys)
            serve("amsmst.cod.zip", ams)
        }

        @Test
        fun `탭 구분에서 거래소 심볼 이름을 뽑는다`() {
            exchanges(nas = overseas("NAS", "AAPL", "애플", "APPLE INC"))

            val s = master.fetchOverseas(at).single()

            listOf(s.exchange, s.symbol, s.name, s.englishName) shouldBe listOf("NAS", "AAPL", "애플", "APPLE INC")
        }

        @Test
        fun `세 거래소를 모두 합친다`() {
            exchanges(overseas("NAS", "AAPL", "애플", "APPLE INC"), overseas("NYS", "JPM", "제이피모건", "JPMORGAN CHASE"), overseas("AMS", "IMO", "임페리얼오일", "IMPERIAL OIL"))

            master.fetchOverseas(at).map { it.exchange }.toSet() shouldBe setOf("NAS", "NYS", "AMS")
        }

        @Test
        fun `한글명이 비면 심볼로 대체한다`() {
            exchanges(nas = overseas("NAS", "TSLA", "", "TESLA INC"))

            master.fetchOverseas(at)[0].name shouldBe "TSLA"
        }

        /** 옵션 만기 문구 등이 붙어 120자를 넘는 경우가 있다 — 넘치면 INSERT가 깨진다. */
        @Test
        fun `영문명이 길면 컬럼 길이까지만 남긴다`() {
            exchanges(nas = overseas("NAS", "XXXX", "긴이름", "A".repeat(200)))

            master.fetchOverseas(at)[0].englishName.length shouldBe 120
        }

        @Test
        fun `컬럼이 모자란 줄은 건너뛴다`() {
            exchanges(nas = "NAS\tAAPL\n" + overseas("NAS", "MSFT", "마이크로소프트", "MICROSOFT"))

            master.fetchOverseas(at).map { it.symbol } shouldBe listOf("MSFT")
        }

        @Test
        fun `심볼이 빈 줄은 건너뛴다`() {
            exchanges(nas = overseas("NAS", "", "이름만", "NAME ONLY"))

            master.fetchOverseas(at).shouldBeEmpty()
        }
    }

    /** 폴백(직전 DB 데이터)은 상위에서 처리한다 — 여기선 실패를 숨기지 않는다. */
    @Nested
    inner class 다운로드_실패 {
        @Test
        fun `오류 응답이면 예외로 올린다`() {
            server.stubFor(get(urlPathEqualTo("/kospi_code.mst.zip")).willReturn(aResponse().withStatus(500)))

            shouldThrowAny { master.fetchDomestic(at) }
        }

        @Test
        fun `zip이 비어있으면 예외로 올린다`() {
            val empty = ByteArrayOutputStream().also { ZipOutputStream(it).close() }.toByteArray()
            server.stubFor(get(urlPathEqualTo("/kospi_code.mst.zip")).willReturn(aResponse().withBody(empty)))

            shouldThrowAny { master.fetchDomestic(at) }
        }
    }

    companion object {
        private const val KOSPI = 228
        private const val KOSDAQ = 222
    }
}
