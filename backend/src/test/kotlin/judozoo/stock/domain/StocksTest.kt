package judozoo.stock.domain

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class StocksTest {
    private val at = LocalDateTime.of(2026, 9, 12, 8, 30)
    private fun stock(code: String, name: String) = Stock(code, "KR7$code", name, Market.KOSPI, at, at)

    private val samsung = stock("005930", "삼성전자")
    private val samsungBio = stock("207940", "삼성바이오로직스")
    private val hynix = stock("000660", "SK하이닉스")
    private val kodex = stock("069500", "KODEX 200")
    private val catalog = Stocks(listOf(samsung, samsungBio, hynix, kodex))

    @Nested
    inner class 종목_검색 {
        @Test
        fun `종목명 일부로 여러 종목을 찾는다`() {
            catalog.search("삼성", limit = 10).map { it.name } shouldBe listOf("삼성바이오로직스", "삼성전자")
        }

        @Test
        fun `종목코드 접두사로 찾는다`() {
            catalog.search("0059", limit = 10) shouldBe listOf(samsung)
        }

        @Test
        fun `대소문자를 가리지 않는다`() {
            catalog.search("kodex", limit = 10) shouldBe listOf(kodex)
        }

        @Test
        fun `종목명 시작 일치를 코드 시작 일치보다 앞에 둔다`() {
            val nameFirst = stock("999999", "ABC전자")
            val codeFirst = stock("ABC123", "주식회사가나")

            Stocks(listOf(codeFirst, nameFirst)).search("abc", limit = 10) shouldBe listOf(nameFirst, codeFirst)
        }

        @Test
        fun `최대 건수를 초과하지 않는다`() {
            catalog.search("삼성", limit = 1) shouldHaveSize 1
        }

        @Test
        fun `공백 질의는 빈 결과`() {
            catalog.search("   ", limit = 10).shouldBeEmpty()
        }

        @Test
        fun `일치하는 종목이 없으면 빈 결과`() {
            catalog.search("없는종목", limit = 10).shouldBeEmpty()
        }
    }

    @Nested
    inner class 단축코드로_찾기 {
        @Test
        fun `거래소 접미사가 붙어 와도 찾는다`() {
            catalog.find("005930_AL") shouldBe samsung
        }
    }

    @Nested
    inner class 해외_종목_검색 {
        private val apple = OverseasStock("NAS", "AAPL", "애플", "APPLE INC", at, at)

        @Test
        fun `심볼로 찾는다`() {
            apple.matches("aapl") shouldBe true
        }

        @Test
        fun `한글명으로 찾는다`() {
            apple.matches("애플") shouldBe true
        }

        @Test
        fun `영문명으로 찾는다`() {
            apple.matches("apple") shouldBe true
        }

        @Test
        fun `무관한 질의는 매칭되지 않는다`() {
            apple.matches("삼성") shouldBe false
        }
    }
}
