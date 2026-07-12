package at.backend.stock.infrastructure

import at.backend.stock.domain.OverseasStock
import org.springframework.web.client.RestClient
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

/**
 * KIS 해외종목 마스터 파일(.cod.zip)을 받아 거래소/심볼/종목명으로 파싱한다.
 *
 * 국내 마스터와 달리 탭 구분 텍스트다(인코딩은 동일하게 MS949).
 * 컬럼: [2] 거래소코드(NAS/NYS/AMS), [4] 심볼, [6] 한글명, [7] 영문명. 나머지 컬럼은 쓰지 않는다.
 */
class KisOverseasStockMasterClient(
    private val restClient: RestClient,
    private val properties: StockMasterProperties,
) {

    fun fetchAll(): List<OverseasStock> =
        properties.overseasUrls.flatMap { url -> parse(download(url)) }

    private fun download(url: String): String {
        val zipped = restClient.get().uri(url).retrieve().body(ByteArray::class.java)
            ?: error("해외 종목 마스터 응답이 비어있습니다: $url")
        ZipInputStream(ByteArrayInputStream(zipped)).use { zip ->
            zip.nextEntry ?: error("해외 종목 마스터 zip이 비어있습니다: $url")
            return zip.readBytes().toString(MS949)
        }
    }

    private fun parse(text: String): List<OverseasStock> =
        text.lineSequence()
            .mapNotNull { line ->
                val cols = line.split('\t')
                if (cols.size <= ENGLISH_NAME) return@mapNotNull null
                val symbol = cols[SYMBOL].trim()
                val exchange = cols[EXCHANGE].trim()
                if (symbol.isEmpty() || exchange.isEmpty()) return@mapNotNull null
                OverseasStock(
                    exchange = exchange,
                    symbol = symbol,
                    name = cols[KOREAN_NAME].trim().ifEmpty { symbol },
                    englishName = cols[ENGLISH_NAME].trim().take(ENGLISH_NAME_MAX),
                )
            }
            .toList()

    companion object {
        private val MS949: Charset = Charset.forName("MS949")
        private const val EXCHANGE = 2
        private const val SYMBOL = 4
        private const val KOREAN_NAME = 6
        private const val ENGLISH_NAME = 7
        private const val ENGLISH_NAME_MAX = 120 // 컬럼 길이 초과분은 버린다(옵션 만기 문구 등이 붙는 경우가 있다)
    }
}
