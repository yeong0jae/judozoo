package at.backend.stock.infrastructure

import at.backend.stock.domain.Market
import at.backend.stock.domain.Stock
import org.springframework.web.client.RestClient
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

/**
 * KIS 종목정보 마스터 파일(.mst.zip)을 받아 코드/이름으로 파싱한다.
 *
 * 한 라인 = [고정폭 앞부분 part1] + [고정폭 뒷부분 part2(코스피 228자 / 코스닥 222자)].
 * part1: 0..9 단축코드, 9..21 표준코드(ISIN), 21.. 한글종목명. 인코딩은 MS949(cp949).
 * 우리에게 필요한 건 part1뿐이라 part2(60여 필드)는 파싱하지 않는다.
 */
class KisStockMasterClient(
    private val restClient: RestClient,
    private val properties: StockMasterProperties,
) {

    fun fetchAll(): List<Stock> =
        parse(download(properties.kospiUrl), Market.KOSPI, KOSPI_PART2_WIDTH) +
            parse(download(properties.kosdaqUrl), Market.KOSDAQ, KOSDAQ_PART2_WIDTH)

    private fun download(url: String): String {
        val zipped = restClient.get().uri(url).retrieve().body(ByteArray::class.java)
            ?: error("종목 마스터 응답이 비어있습니다: $url")
        ZipInputStream(ByteArrayInputStream(zipped)).use { zip ->
            zip.nextEntry ?: error("종목 마스터 zip이 비어있습니다: $url")
            return zip.readBytes().toString(MS949)
        }
    }

    private fun parse(text: String, market: Market, part2Width: Int): List<Stock> =
        text.lineSequence()
            .map { it.trimEnd('\r', '\n') }
            .filter { it.length > part2Width + PART1_NAME_OFFSET }
            .mapNotNull { line ->
                val part1 = line.substring(0, line.length - part2Width)
                val shortCode = part1.substring(0, 9).trim()
                if (shortCode.isEmpty()) return@mapNotNull null
                Stock(
                    shortCode = shortCode,
                    standardCode = part1.substring(9, 21).trim(),
                    name = part1.substring(PART1_NAME_OFFSET).trim(),
                    market = market,
                )
            }
            .toList()

    companion object {
        private val MS949: Charset = Charset.forName("MS949")
        private const val PART1_NAME_OFFSET = 21
        private const val KOSPI_PART2_WIDTH = 228
        private const val KOSDAQ_PART2_WIDTH = 222
    }
}
