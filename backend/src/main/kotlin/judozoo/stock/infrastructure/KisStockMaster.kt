package judozoo.stock.infrastructure

import judozoo.stock.domain.Market
import judozoo.stock.domain.OverseasStock
import judozoo.stock.domain.Stock
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.io.ByteArrayInputStream
import java.net.http.HttpClient
import java.nio.charset.Charset
import java.time.Duration
import java.time.LocalDateTime
import java.util.zip.ZipInputStream

@Component
class KisStockMaster(private val baseUrl: String = BASE_URL, restClient: RestClient.Builder = RestClient.builder()) {
    private val rest = restClient.clone()
        .requestFactory(
            JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(15)).build(),
            ).apply { setReadTimeout(Duration.ofSeconds(30)) },
        )
        .build()

    fun fetchDomestic(at: LocalDateTime): List<Stock> =
        parseDomestic(download("$baseUrl/kospi_code.mst.zip"), Market.KOSPI, KOSPI_PART2_WIDTH, at) +
            parseDomestic(download("$baseUrl/kosdaq_code.mst.zip"), Market.KOSDAQ, KOSDAQ_PART2_WIDTH, at)

    fun fetchOverseas(at: LocalDateTime): List<OverseasStock> =
        listOf("nasmst.cod.zip", "nysmst.cod.zip", "amsmst.cod.zip").flatMap { parseOverseas(download("$baseUrl/$it"), at) }

    private fun download(url: String): String {
        val body = rest.get().uri(url).retrieve().body(ByteArray::class.java)
        if (body == null || body.isEmpty()) throw IllegalStateException("종목 마스터 응답이 비어있습니다: $url")
        ZipInputStream(ByteArrayInputStream(body)).use { zip ->
            zip.nextEntry ?: throw IllegalStateException("종목 마스터 zip이 비어있습니다: $url")
            return String(zip.readAllBytes(), MS949)
        }
    }

    companion object {
        const val BASE_URL = "https://new.real.download.dws.co.kr/common/master"

        private val MS949: Charset = Charset.forName("MS949")

        private const val PART1_NAME_OFFSET = 21
        private const val KOSPI_PART2_WIDTH = 228
        private const val KOSDAQ_PART2_WIDTH = 222

        private const val EXCHANGE_COL = 2
        private const val SYMBOL_COL = 4
        private const val KOREAN_NAME_COL = 6
        private const val ENGLISH_NAME_COL = 7

        private const val ENGLISH_NAME_MAX = 120

        private fun parseDomestic(text: String, market: Market, part2Width: Int, at: LocalDateTime): List<Stock> =
            text.lineSequence().map { it.trimEnd('\r', '\n') }.filter { it.length > part2Width + PART1_NAME_OFFSET }.mapNotNull { line ->
                val part1 = line.substring(0, line.length - part2Width)
                val shortCode = part1.substring(0, 9).trim().ifEmpty { return@mapNotNull null }
                Stock(shortCode, part1.substring(9, 21).trim(), part1.substring(PART1_NAME_OFFSET).trim(), market, at, at)
            }.toList()

        private fun parseOverseas(text: String, at: LocalDateTime): List<OverseasStock> =
            text.lines().map { it.split("\t") }.filter { it.size > ENGLISH_NAME_COL }.mapNotNull { cols ->
                val symbol = cols[SYMBOL_COL].trim()
                val exchange = cols[EXCHANGE_COL].trim()
                if (symbol.isEmpty() || exchange.isEmpty()) return@mapNotNull null
                OverseasStock(exchange, symbol, cols[KOREAN_NAME_COL].trim().ifEmpty { symbol }, cols[ENGLISH_NAME_COL].trim().take(ENGLISH_NAME_MAX), at, at)
            }
    }
}
