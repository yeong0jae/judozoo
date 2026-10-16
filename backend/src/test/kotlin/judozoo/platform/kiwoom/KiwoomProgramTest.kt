package judozoo.platform.kiwoom

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration

class KiwoomProgramTest {
    private val stub = KiwoomStub()
    private val program = KiwoomProgram(stub.client, stub.clock)

    @AfterEach
    fun 정리() = stub.close()

    @Nested
    inner class 종목_프로그램매매 {
        @Test
        fun `첫 행의 순매수를 읽는다`() {
            stub.respond(
                URL,
                """{"stk_daly_prm_trde_trnsn":[{"dt":"20260911","prm_sell_amt":"1000","prm_buy_amt":"1500","prm_netprps_amt":"+500"}]}""",
            )

            program.fetchProgramTrading("005930")!!.programNetBuyAmount shouldBe 500
        }

        @Test
        fun `같은 종목은 캐시하고 날짜가 바뀌면 다시 조회한다`() {
            stub.respond(URL, """{"stk_daly_prm_trde_trnsn":[{"dt":"20260930","prm_netprps_amt":"500"}]}""")

            program.fetchProgramNetBuy("005930") shouldBe 500
            program.fetchProgramNetBuy("005930") shouldBe 500
            stub.calls(URL) shouldBe 1

            program.fetchProgramNetBuy("000660") shouldBe 500
            stub.calls(URL) shouldBe 2

            stub.now += Duration.ofDays(1)
            program.fetchProgramNetBuy("005930") shouldBe 500
            stub.calls(URL) shouldBe 3
        }

        /** 운영 로그에서 이 필드가 없는 응답이 확인됐다. */
        @Test
        fun `목록이 통째로 빠져도 null로 넘긴다`() {
            stub.respond(URL, "{}")

            program.fetchProgramTrading("005930").shouldBeNull()
            program.fetchProgramNetBuy("005930") shouldBe 0
            stub.calls(URL) shouldBe 2
        }
    }

    @Nested
    inner class 금액_파서 {
        /** "--123"이 실제로 온다 — 앞의 하나만 떼면 부호가 뒤집힌다. */
        @Test
        fun `음수 변종을 모두 흡수한다`() {
            mapOf("-123" to -123L, "123-" to -123L, "--123" to -123L, "+123" to 123L, "123" to 123L, "" to 0L, "abc" to 0L)
                .forEach { (input, expected) -> KiwoomProgram.parseAmount(input) shouldBe expected }
        }
    }

    companion object {
        private const val URL = "/api/dostk/mrkcond"
    }
}
