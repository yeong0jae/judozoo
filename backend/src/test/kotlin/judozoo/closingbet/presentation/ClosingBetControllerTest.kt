package judozoo.closingbet.presentation

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import judozoo.LoggedIn
import judozoo.closingbet.application.ClosingBetService
import judozoo.closingbet.application.ClosingBetService.PoolStock
import judozoo.closingbet.application.ClosingBetService.Readings
import judozoo.closingbet.application.ClosingBetService.StockFlows
import judozoo.closingbet.application.ClosingBetService.StockReading
import judozoo.closingbet.domain.BetPlayer
import judozoo.closingbet.domain.BetRejected
import judozoo.closingbet.domain.BetRound
import judozoo.closingbet.domain.BetRoundStock
import judozoo.closingbet.domain.Checks
import judozoo.closingbet.domain.ClosingBet
import judozoo.closingbet.domain.Moment
import judozoo.closingbet.domain.NicknameRejected
import judozoo.closingbet.domain.Outcome
import judozoo.closingbet.domain.Phase
import judozoo.closingbet.domain.Result
import judozoo.closingbet.domain.Results
import judozoo.closingbet.domain.Stake
import judozoo.closingbet.domain.Stakes
import judozoo.stock.domain.Market
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import java.time.LocalDate
import java.time.LocalDateTime

@WebMvcTest(ClosingBetController::class)
@Import(LoggedIn.ClockAtSigning::class)
@TestPropertySource(properties = [LoggedIn.SECRET])
class ClosingBetControllerTest @Autowired constructor(private val mvc: MockMvc) {
    @MockkBean
    private lateinit var service: ClosingBetService

    private val thu = LocalDate.of(2026, 10, 1)
    private val bet = Phase(Moment.BET, thu, LocalDate.of(2026, 9, 30), LocalDateTime.of(2026, 10, 1, 20, 0))
    private val hynix = PoolStock("000660", "SK하이닉스", true, 412_500, 6.82)
    private val checks = Checks.of(412_500, 414_000, 2_640, 910, 740, -0.4, -5.0, 410_000)

    private fun open(code: String = "000660", name: String = "SK하이닉스", amount: Int = 2_000) = ClosingBet(7, thu, code, name, amount, LocalDateTime.of(2026, 10, 1, 15, 0))

    @BeforeEach
    fun 베팅_시간() {
        every { service.phase(any()) } returns bet
        every { service.pool() } returns listOf(hynix)
        every { service.stakes(thu) } returns Stakes(listOf(Stake(1, "000660", 2_000, LocalDateTime.of(2026, 10, 1, 15, 0))))
        val reading = StockReading(hynix, Market.KOSPI, 412_500, 414_000, 386_000, StockFlows(2_640, 910, 8_420, 2_150), -0.4, 410_000, checks)
        every { service.readings(thu) } returns Readings(mapOf("KOSPI" to emptyMap<String, Any>()), mapOf("000660" to reading), mapOf("000660" to true))
    }

    @Nested
    inner class 지금 {
        @Test
        fun `방문자는 판돈과 참여 수까지`() {
            mvc.get("/api/closingbet/now").andExpect {
                content {
                    json(
                        """{"code":"SUCCESS","status":200,"data":{"moment":"bet","bettingDay":"2026-10-01","reviewDay":"2026-09-30",
                        "nextAt":"2026-10-01T20:00:00","potMan":2000,"players":1,"me":null}}""",
                        JsonCompareMode.STRICT,
                    )
                }
            }
        }

        @Test
        fun `로그인하면 내 닉네임과 베팅이 실린다`() {
            every { service.findPlayer(7) } returns BetPlayer(7, "용감한수달", LocalDateTime.of(2026, 10, 1, 9, 0))
            every { service.myBet(7, thu) } returns open()

            mvc.get("/api/closingbet/now") { cookie(LoggedIn.cookie) }.andExpect {
                content {
                    json(
                        """{"data":{"me":{"nickname":"용감한수달","nextRenameAt":null,"bet":{"stockCode":"000660","stockName":"SK하이닉스",
                        "amountMan":2000,"status":"open","buyPrice":null,"shares":null}}}}""",
                        JsonCompareMode.LENIENT,
                    )
                }
            }
        }

        @Test
        fun `아직 한 번도 안 걸었으면 닉네임이 없다`() {
            every { service.findPlayer(7) } returns null
            every { service.myBet(7, thu) } returns null

            mvc.get("/api/closingbet/now") { cookie(LoggedIn.cookie) }.andExpect {
                content { json("""{"data":{"me":{"nickname":null,"nextRenameAt":null,"bet":null}}}""", JsonCompareMode.LENIENT) }
            }
        }
    }

    @Nested
    inner class 오늘의_종목 {
        @Test
        fun `방문자는 등급까지만 본다`() {
            mvc.get("/api/closingbet/stocks").andExpect {
                content {
                    json(
                        """{"data":[{"code":"000660","name":"SK하이닉스","lead":true,"price":412500,"changeRate":6.82,"market":"KOSPI","nxt":true,
                        "grade":"S","crowd":1,"potMan":2000,"high":null,"low":null,"foreign":null,"institution":null,"foreignWeek":null,
                        "institutionWeek":null,"recentHighGap":null,"regularClose":null,"checks":null}]}""",
                        JsonCompareMode.LENIENT,
                    )
                }
            }
        }

        @Test
        fun `로그인하면 등급 이유가 실린다`() {
            mvc.get("/api/closingbet/stocks") { cookie(LoggedIn.cookie) }.andExpect {
                content {
                    json(
                        """{"data":[{"checks":{"nearHigh":true,"foreign":true,"institution":true,"marketLate":true,"recentHigh":true,"afterHold":true},
                        "foreignWeek":8420,"recentHighGap":-0.4,"high":414000,"regularClose":410000}]}""",
                        JsonCompareMode.LENIENT,
                    )
                }
            }
        }

        @Test
        fun `시장 수급 표는 로그인 뒤다`() {
            mvc.get("/api/closingbet/market").andExpect { status { isUnauthorized() } }
            mvc.get("/api/closingbet/market") { cookie(LoggedIn.cookie) }.andExpect { content { json("""{"data":{"KOSPI":{}}}""", JsonCompareMode.LENIENT) } }
        }

        @Test
        fun `베팅 시간 밖에는 빈 목록이다`() {
            every { service.phase(any()) } returns Phase(Moment.REVIEW, null, thu, null)

            mvc.get("/api/closingbet/stocks").andExpect { jsonPath("$.data") { isEmpty() } }
            mvc.get("/api/closingbet/feed").andExpect { jsonPath("$.data") { isEmpty() } }
        }
    }

    @Nested
    inner class 베팅_API {
        @Test
        fun `방문자는 걸 수 없다`() {
            mvc.put("/api/closingbet/bet") { contentType = MediaType.APPLICATION_JSON; content = """{"code":"000660","amountMan":2000}""" }
                .andExpect { status { isUnauthorized() } }
        }

        @Test
        fun `거절되면 이유 문장을 돌려준다`() {
            every { service.place(7, "000660", 2_000, any()) } throws BetRejected("베팅은 15:00부터 20:00까지예요")

            mvc.put("/api/closingbet/bet") {
                cookie(LoggedIn.cookie); contentType = MediaType.APPLICATION_JSON; content = """{"code":"000660","amountMan":2000}"""
            }.andExpect {
                status { isConflict() }
                content { json("""{"code":"BET_REJECTED","status":409,"data":{"message":"베팅은 15:00부터 20:00까지예요"}}""", JsonCompareMode.STRICT) }
            }
        }

        @Test
        fun `종목 코드가 6자리가 아니면 받지 않는다`() {
            mvc.put("/api/closingbet/bet") {
                cookie(LoggedIn.cookie); contentType = MediaType.APPLICATION_JSON; content = """{"code":"000660_AL","amountMan":2000}"""
            }.andExpect { jsonPath("$.code") { value("INVALID_PARAMETER") } }
        }

        @Test
        fun `걸면 내 베팅을 돌려준다`() {
            every { service.place(7, "000660", 2_000, any()) } returns open()

            mvc.put("/api/closingbet/bet") {
                cookie(LoggedIn.cookie); contentType = MediaType.APPLICATION_JSON; content = """{"code":"000660","amountMan":2000}"""
            }.andExpect { jsonPath("$.data.stockName") { value("SK하이닉스") } }
        }

        @Test
        fun `취소하면 빈 봉투를 돌려준다`() {
            every { service.cancel(7, any()) } returns Unit

            mvc.delete("/api/closingbet/bet") { cookie(LoggedIn.cookie) }.andExpect { content { json("""{"code":"SUCCESS","status":200,"data":null}""", JsonCompareMode.STRICT) } }
            verify { service.cancel(7, any()) }
        }
    }

    @Nested
    inner class 닉네임_API {
        @Test
        fun `거절되면 이유 문장을 돌려준다`() {
            every { service.rename(7, "새벽올빼미", any()) } throws NicknameRejected("이미 있는 닉네임이에요")

            mvc.put("/api/closingbet/me/nickname") {
                cookie(LoggedIn.cookie); contentType = MediaType.APPLICATION_JSON; content = """{"nickname":"새벽올빼미"}"""
            }.andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("NICKNAME_REJECTED") }
                jsonPath("$.data.message") { value("이미 있는 닉네임이에요") }
            }
        }
    }

    @Nested
    inner class 복기_API {
        private fun round() {
            every { service.betRound(thu) } returns BetRound(thu, LocalDateTime.of(2026, 10, 1, 20, 1), """{"KOSPI":{}}""", 2, 4_000).apply { settle(LocalDateTime.of(2026, 10, 2, 9, 5)) }
            every { service.roundStocks(thu) } returns listOf(
                BetRoundStock(
                    thu, "000660", "SK하이닉스", "KOSPI", true, true, 400_000, 401_000, 390_000, 3.1f, 100, 50, 500, 200, -0.4f, null,
                    """{"near_high":true,"foreign":true,"institution":true,"market_late":true,"recent_high":true}""", "S", 2, 4_000,
                ),
            )
            every { service.results(thu) } returns Results(
                listOf(
                    Result(1, "000660", LocalDateTime.of(2026, 10, 1, 15, 0), Outcome(400_000, 2.0)),
                    Result(7, "000660", LocalDateTime.of(2026, 10, 1, 16, 0), Outcome(400_000, 2.0)),
                ),
            )
            every { service.nicknames(any()) } returns mapOf(1L to "새벽올빼미", 7L to "용감한수달")
        }

        @Test
        fun `방문자는 결과와 랭킹을 본다`() {
            round()

            mvc.get("/api/closingbet/rounds/2026-10-01").andExpect {
                jsonPath("$.data.settled") { value(true) }
                jsonPath("$.data.stocks[0].checks") { value(null) }
                jsonPath("$.data.byProfit[0].nickname") { value("새벽올빼미") }
                jsonPath("$.data.byProfit[1].rank") { value(1) }
                jsonPath("$.data.byProfit[1].nickname") { value("용감한수달") }
                jsonPath("$.data.me") { value(null) }
                jsonPath("$.data.market") { value(null) }
            }
        }

        /** 애프터 버팀이 생기기 전에 찍힌 판에는 그 키가 없다 — false로 읽는다. */
        @Test
        fun `로그인하면 내 결과와 순위와 시장 수급이 실린다`() {
            round()
            every { service.myBet(7, thu) } returns open().apply { fill(400_000); settle(408_000.0) }

            mvc.get("/api/closingbet/rounds/2026-10-01") { cookie(LoggedIn.cookie) }.andExpect {
                jsonPath("$.data.me.pnl") { value(400_000) }
                jsonPath("$.data.me.profitRank") { value(1) }
                jsonPath("$.data.market.KOSPI") { isMap() }
                jsonPath("$.data.stocks[0].checks.afterHold") { value(false) }
                jsonPath("$.data.stocks[0].changeRate") { value(3.1) }
            }
        }

        @Test
        fun `없는 판은 404`() {
            every { service.betRound(LocalDate.of(2026, 10, 3)) } returns null

            mvc.get("/api/closingbet/rounds/2026-10-03").andExpect { status { isNotFound() } }
        }
    }
}
