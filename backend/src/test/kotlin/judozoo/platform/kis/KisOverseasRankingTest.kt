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
        /** 등락 방향은 sign이 정하고 diff·rate는 크기라, 파싱은 쓰는 쪽에 맡긴다. */
        @Test
        fun `응답 필드를 그대로 전달한다`() {
            stub.respond(
                URL,
                """{"rt_cd":"0","msg_cd":"","msg1":"","output2":[{"rank":"1","excd":"NAS","symb":"AAPL","name":"애플","last":"230.5",
                "sign":"5","diff":"1.2","rate":"0.52","tvol":"50000000","tamt":"11500000000","ename":"APPLE INC"}]}""",
            )

            val item = ranking.fetchTradingValueRanking("NAS")[0]

            item.symb shouldBe "AAPL"
            item.sign shouldBe "5" // 5=하락
            item.rate shouldBe "0.52" // 크기 그대로
        }

        @Test
        fun `없는 필드는 빈 문자열로 채운다`() {
            stub.respond(URL, """{"rt_cd":"0","msg1":"","output2":[{"symb":"AAPL"}]}""")

            ranking.fetchTradingValueRanking("NAS")[0].ename shouldBe ""
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
