package judozoo.platform.kis

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class KisOverseasRankingTest {
    private val stub = KisStub()
    private val ranking = KisOverseasRanking(stub.client)

    @AfterEach
    fun 정리() = stub.close()

    @Nested
    inner class 거래대금순위 {
        @Test
        fun `가격을 숫자로 읽고 하락 부호를 대비값에 붙인다`() {
            stub.respond(
                URL,
                """{"rt_cd":"0","msg_cd":"","msg1":"","output2":[{"rank":"1","excd":"NAS","symb":"AAPL","name":"애플","last":"230.5",
                "sign":"5","diff":"1.2","rate":"0.52","tvol":"50000000","tamt":"11500000000","ename":"APPLE INC"}]}""",
            )

            val item = ranking.fetchTradingValueRanking("NAS")[0]

            item.symbol shouldBe "AAPL"
            item.exchange shouldBe "NAS"
            item.price shouldBe 230.5
            item.diff shouldBe -1.2
            item.rate shouldBe 0.52
            item.tradingValue shouldBe 11_500_000_000.0
        }

        @Test
        fun `없는 필드는 빈 이름과 0으로 채운다`() {
            stub.respond(URL, """{"rt_cd":"0","msg1":"","output2":[{"symb":"AAPL"}]}""")

            val item = ranking.fetchTradingValueRanking("NAS")[0]
            item.ename shouldBe ""
            item.price shouldBe 0.0
        }

        @Test
        fun `오류 코드가 오면 예외를 올린다`() {
            stub.respond(URL, """{"rt_cd":"1","msg1":"조회 실패"}""")

            shouldThrow<IllegalStateException> { ranking.fetchTradingValueRanking("NAS") }.message shouldContain "거래대금순위 오류"
        }
    }

    companion object {
        private const val URL = "/uapi/overseas-stock/v1/ranking/trade-pbmn"
    }
}
