package judozoo.stock.application

import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.verify
import judozoo.IntegrationTest
import judozoo.stock.domain.Market
import judozoo.stock.domain.OverseasStock
import judozoo.stock.domain.Stock
import judozoo.stock.infrastructure.KisStockMaster
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDate
import java.time.LocalDateTime

@IntegrationTest
class StockCatalogTest @Autowired constructor(
    private val catalog: StockCatalog,
    private val jdbc: JdbcTemplate,
) {
    @MockkBean
    private lateinit var master: KisStockMaster

    private val today = LocalDate.of(2026, 9, 12)

    private fun stock(code: String, name: String, at: LocalDateTime) = Stock(code, "KR7$code", name, Market.KOSPI, at, at)

    @BeforeEach
    fun 빈_테이블() {
        jdbc.update("DELETE FROM stocks")
        jdbc.update("DELETE FROM overseas_stocks")
        catalog.reload()
    }

    private fun storedRows() {
        jdbc.update(
            "INSERT INTO stocks (short_code, standard_code, name, market, created_at, updated_at) VALUES " +
                "('005930','KR7005930003','삼성전자','KOSPI',NOW(),NOW()), ('247540','KR7247540008','에코프로비엠','KOSDAQ',NOW(),NOW())",
        )
        jdbc.update(
            "INSERT INTO overseas_stocks (exchange, symbol, name, english_name, created_at, updated_at) VALUES " +
                "('NAS','AAPL','애플','APPLE INC',NOW(),NOW())",
        )
    }

    @Nested
    inner class 적재 {
        @Test
        fun `DB의 카탈로그를 읽어 검색한다`() {
            storedRows()

            catalog.reload()

            catalog.sizes() shouldBe StockCatalog.Sizes(2, 1)
            catalog.search("삼성").single().stockCode shouldBe "005930"
            catalog.search("애플").single().exchange shouldBe "NAS"
        }

        @Test
        fun `시장 구분을 읽는다`() {
            storedRows()

            catalog.reload()

            catalog.marketOf("247540_AL") shouldBe Market.KOSDAQ
        }

        @Test
        fun `비어 있으면 빈 카탈로그로 남는다`() {
            catalog.reload()

            catalog.sizes() shouldBe StockCatalog.Sizes(0, 0)
            catalog.search("삼성").shouldBeEmpty()
        }
    }

    @Nested
    inner class 통합_검색 {
        @Test
        fun `국내를 앞에 두고 해외를 뒤에 붙인다`() {
            jdbc.update(
                "INSERT INTO stocks (short_code, standard_code, name, market, created_at, updated_at) VALUES ('900110','HK0000057197','애플라인','KOSDAQ',NOW(),NOW())",
            )
            storedRows()
            catalog.reload()

            catalog.search("애플").map { it.stockName } shouldBe listOf("애플라인", "애플")
        }
    }

    @Nested
    inner class 카탈로그_갱신 {
        private fun inserted(at: String) = jdbc.update(
            "INSERT INTO stocks (short_code, standard_code, name, market, created_at, updated_at) VALUES ('005930','KR7005930003','삼성전자','KOSPI',?,?)", at, at,
        )

        @Test
        fun `오늘 이미 동기화됐으면 다운로드하지 않고 DB에서 적재한다`() {
            inserted("2026-09-12 08:30:00")
            jdbc.update("INSERT INTO overseas_stocks (exchange, symbol, name, english_name, created_at, updated_at) VALUES ('NAS','AAPL','AAPL','AAPL','2026-09-12 08:30:00','2026-09-12 08:30:00')")

            catalog.refresh(today)

            catalog.sizes() shouldBe StockCatalog.Sizes(1, 1)
            catalog.search("삼성")[0].stockCode shouldBe "005930"
            verify(exactly = 0) { master.fetchDomestic(any()) }
            verify(exactly = 0) { master.fetchOverseas(any()) }
        }

        @Test
        fun `동기화가 어제 것이면 새로 받아 전체 교체한다`() {
            jdbc.update("INSERT INTO stocks (short_code, standard_code, name, market, created_at, updated_at) VALUES ('000000','KR7000000000','옛날종목','KOSPI','2026-09-11 08:30:00','2026-09-11 08:30:00')")
            every { master.fetchDomestic(any()) } returns listOf(stock("005930", "삼성전자", LocalDateTime.of(2026, 9, 12, 7, 40)))
            every { master.fetchOverseas(any()) } returns listOf(OverseasStock("NAS", "AAPL", "애플", "APPLE INC", LocalDateTime.of(2026, 9, 12, 7, 40), LocalDateTime.of(2026, 9, 12, 7, 40)))

            catalog.refresh(today)

            jdbc.queryForList("SELECT short_code FROM stocks", String::class.java) shouldBe listOf("005930")
            catalog.sizes() shouldBe StockCatalog.Sizes(1, 1)
        }

        @Test
        fun `다운로드가 실패하면 DB의 직전 데이터로 폴백한다`() {
            inserted("2026-09-11 08:30:00")
            every { master.fetchDomestic(any()) } throws IllegalStateException("CDN 죽음")
            every { master.fetchOverseas(any()) } returns emptyList()

            catalog.refresh(today)

            catalog.sizes().domestic shouldBe 1
            catalog.search("삼성")[0].stockName shouldBe "삼성전자"
        }

        @Test
        fun `다운로드도 DB도 비어있으면 빈 카탈로그로 남는다`() {
            every { master.fetchDomestic(any()) } throws IllegalStateException("CDN 죽음")
            every { master.fetchOverseas(any()) } throws IllegalStateException("CDN 죽음")

            catalog.refresh(today)

            catalog.sizes() shouldBe StockCatalog.Sizes(0, 0)
            catalog.search("삼성").shouldBeEmpty()
        }
    }
}
