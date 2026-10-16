package judozoo.platform.kiwoom

import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import judozoo.stock.domain.Market
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class KiwoomIndexTest {
    private val stub = KiwoomStub()
    private val index = KiwoomIndex(stub.client, stub.clock)
    private val day = LocalDate.of(2026, 9, 11)

    @AfterEach
    fun 정리() = stub.close()

    @Nested
    inner class 업종_지수 {
        @Test
        fun `지수값은 절대값 등락률은 부호를 살린다`() {
            stub.respond(URL, """{"return_code":0,"cur_prc":"-2653.81","flu_rt":"-1.25"}""")

            val result = index.fetchIndex(Market.KOSPI)!!

            result.currentValue shouldBe 2653.81
            result.changeRate shouldBe -1.25
        }

        @Test
        fun `오류 코드면 null`() {
            stub.respond(URL, """{"return_code":3,"return_msg":"오류"}""")

            index.fetchIndex(Market.KOSPI).shouldBeNull()
        }

        @Test
        fun `연결이 끊기면 null`() {
            stub.fail(URL)

            index.fetchIndex(Market.KOSPI).shouldBeNull()
        }

        @Test
        fun `10초 틱에 조회일을 붙여 시각을 만든다`() {
            stub.respond(
                URL,
                """{"return_code":0,"cur_prc":"+2653.81","flu_rt":"+1.25","inds_cur_prc_tm":[
                {"tm_n":"143000","cur_prc_n":"-2650.00","trde_qty_n":"1200"},
                {"tm_n":"090000","cur_prc_n":"+2600.00","trde_qty_n":"800"}]}""",
            )

            val result = index.fetchIndexIntraday(Market.KOSPI, day)!!

            result.ticks.map { it.at.toLocalTime() } shouldBe listOf(LocalTime.of(9, 0), LocalTime.of(14, 30))
            result.ticks[1].value shouldBe 2650.00
            result.ticks[0].at.toLocalDate() shouldBe day
        }

        @Test
        fun `시각 형식이 어긋난 틱은 버린다`() {
            stub.respond(
                URL,
                """{"return_code":0,"cur_prc":"2653.81","flu_rt":"0","inds_cur_prc_tm":[
                {"tm_n":"1430","cur_prc_n":"2650.00"},{"tm_n":"143000","cur_prc_n":"2650.00"}]}""",
            )

            index.fetchIndexIntraday(Market.KOSPI, day)!!.ticks shouldHaveSize 1
        }

        @Test
        fun `시장별로 업종코드가 갈린다`() {
            stub.server.stubFor(
                post(urlEqualTo(URL)).withRequestBody(equalToJson("""{"mrkt_tp":"1","inds_cd":"101"}"""))
                    .willReturn(okJson("""{"return_code":0,"cur_prc":"1","flu_rt":"0"}""")),
            )

            index.fetchIndexIntraday(Market.KOSDAQ, day).shouldNotBeNull()
        }
    }

    companion object {
        private const val URL = "/api/dostk/sect"
    }
}
