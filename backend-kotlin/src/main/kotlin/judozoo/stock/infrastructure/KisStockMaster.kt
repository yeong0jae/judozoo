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

/**
 * KIS 종목 마스터 파일(.zip) 다운로드·파싱.
 *
 * 평문 CDN이라 OpenAPI 토큰·레이트리밋과 무관하다 — 인증도 공유 리미터도 타지 않는다.
 * 인코딩은 국내·해외 모두 MS949. 파싱 실패가 조용히 빈 카탈로그로 이어지므로 포맷 함정을 여기서 막는다.
 */
@Component
class KisStockMaster(private val baseUrl: String = BASE_URL) {
    private val rest = RestClient.builder()
        .requestFactory(
            JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(15)).build(),
            ).apply { setReadTimeout(Duration.ofSeconds(30)) },
        )
        .build()

    /** 코스피 + 코스닥 전 종목. */
    fun fetchDomestic(at: LocalDateTime): List<Stock> =
        parseDomestic(download("$baseUrl/kospi_code.mst.zip"), Market.KOSPI, KOSPI_PART2_WIDTH, at) +
            parseDomestic(download("$baseUrl/kosdaq_code.mst.zip"), Market.KOSDAQ, KOSDAQ_PART2_WIDTH, at)

    /** 나스닥 · 뉴욕 · 아멕스 전 종목. */
    fun fetchOverseas(at: LocalDateTime): List<OverseasStock> =
        listOf("nasmst.cod.zip", "nysmst.cod.zip", "amsmst.cod.zip").flatMap { parseOverseas(download("$baseUrl/$it"), at) }

    /** 오류 응답·빈 응답·빈 zip은 예외로 올린다 — 폴백(직전 DB 데이터)은 부르는 쪽이 한다. */
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

        /**
         * 국내 마스터 — 한 라인 = [고정폭 part1] + [고정폭 part2]. part1: 0..9 단축코드, 9..21 표준코드(ISIN), 21.. 한글종목명.
         * 필요한 건 part1뿐이라 part2(60여 필드)는 파싱하지 않는다. **part2 폭이 시장마다 다르다** — 잘못 쓰면 종목명 끝에 쓰레기가 붙는다.
         */
        private const val PART1_NAME_OFFSET = 21
        private const val KOSPI_PART2_WIDTH = 228
        private const val KOSDAQ_PART2_WIDTH = 222

        /** 해외 마스터 — 탭 구분. 나머지 컬럼은 쓰지 않는다. */
        private const val EXCHANGE_COL = 2
        private const val SYMBOL_COL = 4
        private const val KOREAN_NAME_COL = 6
        private const val ENGLISH_NAME_COL = 7

        /** 컬럼 길이 초과분은 버린다(옵션 만기 문구 등이 붙는 경우가 있다) — 넘치면 INSERT가 깨진다 */
        private const val ENGLISH_NAME_MAX = 120

        /** 문자 수로 자른다 — Python 슬라이스와 같다. 한글 한 글자도 한 칸이다. */
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
