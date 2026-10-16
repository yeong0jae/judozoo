package judozoo.platform.kiwoom

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class KiwoomSectorInvestorTest {
    private val stub = KiwoomStub()
    private val sector = KiwoomSectorInvestor(stub.client)

    @AfterEach
    fun 정리() = stub.close()

    private fun response(name: String = "종합(KOSPI)") =
        """{"return_code":0,"inds_netprps":[{"inds_nm":"$name","cur_prc":"+265381","flu_rt":"+352",
        "frgnr_netprps":"+255","orgn_netprps":"-622","ind_netprps":"367","etc_corp_netprps":"0","sc_netprps":"-100",
        "invtrt_netprps":"-200","endw_netprps":"-50","samo_fund_netprps":"-72","insrnc_netprps":"-100",
        "bank_netprps":"-50","jnsinkm_netprps":"-50"}]}"""

    @Nested
    inner class 업종_투자자_순매수 {
        @Test
        fun `지수값과 등락률은 100으로 나눈다`() {
            stub.respond(URL, response())

            val result = sector.fetchSectorNetBuy("0")!!

            result.indexValue shouldBe 2653.81
            result.changeRate shouldBe 3.52
        }

        @Test
        fun `순매수는 부호를 살린 억원`() {
            stub.respond(URL, response())

            val result = sector.fetchSectorNetBuy("0")!!

            listOf(result.foreignEok, result.institutionEok, result.individualEok) shouldBe listOf(255L, -622L, 367L)
        }

        @Test
        fun `종합 행이 없으면 첫 행으로 폴백한다`() {
            stub.respond(URL, response(name = "반도체"))

            sector.fetchSectorNetBuy("0").shouldNotBeNull()
        }

        @Test
        fun `행이 없으면 null`() {
            stub.respond(URL, """{"return_code":0,"inds_netprps":[]}""")

            sector.fetchSectorNetBuy("0").shouldBeNull()
        }
    }

    companion object {
        private const val URL = "/api/dostk/sect"
    }
}
