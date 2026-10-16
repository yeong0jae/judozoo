package judozoo.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class KoreanMoneyTest {
    @Nested
    inner class 억조_표기 {
        @Test
        fun `조 단위는 억이 없으면 조만 쓴다`() {
            KoreanMoney.format(1_073_000_000_000_000) shouldBe "1,073조원"
        }

        @Test
        fun `조와 억이 함께 있으면 둘 다 쓴다`() {
            KoreanMoney.format(1_000_500_000_000) shouldBe "1조 5억원"
        }

        @Test
        fun `억 단위는 억으로 끊는다`() {
            KoreanMoney.format(300_000_000_000) shouldBe "3,000억원"
        }

        @Test
        fun `억 미만은 원으로 쓴다`() {
            KoreanMoney.format(5_000) shouldBe "5,000원"
        }

        @Test
        fun `음수는 부호를 앞에 붙인다`() {
            KoreanMoney.format(-300_000_000_000) shouldBe "-3,000억원"
        }
    }

    @Nested
    inner class 만원까지_표기 {
        @Test
        fun `억과 만을 함께 쓴다`() {
            KoreanMoney.formatWithMan(1_234_567_890) shouldBe "12억 3,456만원"
        }

        @Test
        fun `만 단위가 0이면 생략한다`() {
            KoreanMoney.formatWithMan(1_200_000_000) shouldBe "12억원"
        }

        @Test
        fun `억 미만 만 단위`() {
            KoreanMoney.formatWithMan(50_000) shouldBe "5만원"
        }

        @Test
        fun `만 미만은 원으로 쓴다`() {
            KoreanMoney.formatWithMan(9_999) shouldBe "9,999원"
        }

        @Test
        fun `음수는 부호를 앞에 붙인다`() {
            KoreanMoney.formatWithMan(-1_234_567_890) shouldBe "-12억 3,456만원"
        }

        @Test
        fun `조 단위는 억과 만을 이어 쓴다`() {
            KoreanMoney.formatWithMan(1_000_500_010_000) shouldBe "1조 5억 1만원"
        }
    }

    @Test
    fun `가격은 천단위 쉼표를 넣는다`() {
        KoreanMoney.formatPrice(71_500) shouldBe "71,500원"
    }
}
