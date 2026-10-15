package judozoo.stock.application

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import judozoo.IntegrationTest
import judozoo.stock.domain.Market
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/** 카탈로그 테이블은 Python이 갈아끼운다. 여기서는 Python이 넣은 것처럼 행을 넣고 읽어 온다. */
@IntegrationTest
class StockCatalogTest @Autowired constructor(
    private val catalog: StockCatalog,
    private val jdbc: JdbcTemplate,
) {
    @BeforeEach
    fun 빈_테이블() {
        jdbc.update("DELETE FROM stocks")
        jdbc.update("DELETE FROM overseas_stocks")
        catalog.reload()
    }

    private fun pythonInserts() {
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
        fun `Python이 갈아끼운 카탈로그를 읽어 검색한다`() {
            pythonInserts()

            catalog.reload()

            catalog.sizes() shouldBe (2 to 1)
            catalog.search("삼성").single().stockCode shouldBe "005930"
            catalog.search("애플").single().exchange shouldBe "NAS"
        }

        @Test
        fun `시장 구분을 읽는다`() {
            pythonInserts()

            catalog.reload()

            catalog.marketOf("247540_AL") shouldBe Market.KOSDAQ
        }

        @Test
        fun `비어 있으면 빈 카탈로그로 남는다`() {
            catalog.reload()

            catalog.sizes() shouldBe (0 to 0)
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
            pythonInserts()
            catalog.reload()

            catalog.search("애플").map { it.stockName } shouldBe listOf("애플라인", "애플")
        }
    }
}
