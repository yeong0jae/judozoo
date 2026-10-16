package judozoo.platform.kiwoom

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class KiwoomInvestorTest {
    private val stub = KiwoomStub()
    private val investor = KiwoomInvestor(stub.client, stub.clock)

    @AfterEach
    fun 정리() = stub.close()

    @Nested
    inner class 종목_투자자_수급 {
        @Test
        fun `날짜를 ISO로 바꾸고 부호를 살린다`() {
            stub.respond(
                URL,
                """{"return_code":0,"stk_invsr_orgn":[{"dt":"20260911","ind_invsr":"+1200","frgnr_invsr":"-800",
                "orgn":"400-","etc_corp":"0","fnnc_invt":"+100","insrnc":"-50","invtrt":"+200","etc_fnnc":"0",
                "bank":"-10","penfnd_etc":"+150","samo_fund":"-20"}]}""",
            )

            val day = investor.fetchInvestorTrend("005930")[0]

            day.date shouldBe "2026-09-11"
            day.individualNet shouldBe 1200
            day.foreignNet shouldBe -800
            day.institutionNet shouldBe -400
        }

        @Test
        fun `기관 세부를 모두 읽는다`() {
            stub.respond(
                URL,
                """{"return_code":0,"stk_invsr_orgn":[{"dt":"20260911","fnnc_invt":"100","insrnc":"50","invtrt":"200",
                "etc_fnnc":"10","bank":"20","penfnd_etc":"150","samo_fund":"30"}]}""",
            )

            val day = investor.fetchInvestorTrend("005930")[0]

            listOf(day.financialInvestmentNet, day.trustNet, day.pensionFundNet) shouldBe listOf(100L, 200L, 150L)
            listOf(day.insuranceNet, day.bankNet, day.privateEquityNet) shouldBe listOf(50L, 20L, 30L)
        }

        @Test
        fun `날짜 형식이 어긋나면 빈 문자열`() {
            stub.respond(URL, """{"return_code":0,"stk_invsr_orgn":[{"dt":"2026"}]}""")

            investor.fetchInvestorTrend("005930")[0].date shouldBe ""
        }

        @Test
        fun `오류 코드면 빈 목록`() {
            stub.respond(URL, """{"return_code":3,"return_msg":"오류"}""")

            investor.fetchInvestorTrend("005930").shouldBeEmpty()
        }

        @Test
        fun `연결이 끊기면 빈 목록`() {
            stub.fail(URL)

            investor.fetchInvestorTrend("005930").shouldBeEmpty()
        }
    }

    companion object {
        private const val URL = "/api/dostk/stkinfo"
    }
}
