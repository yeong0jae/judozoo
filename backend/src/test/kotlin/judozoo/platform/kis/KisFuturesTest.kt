package judozoo.platform.kis

import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import judozoo.market.domain.FuturesSession
import judozoo.stock.domain.Market
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class KisFuturesTest {
    private val stub = KisStub()
    private val futures = KisFutures(stub.client)
    private val from = LocalDate.of(2026, 9, 1)
    private val to = LocalDate.of(2026, 9, 11)

    @AfterEach
    fun 정리() = stub.close()

    @Nested
    inner class 근월물 {
        @Test
        fun `잔존일수가 가장 짧은 종목을 고른다`() {
            stub.respond(
                BOARD,
                """{"rt_cd":"0","output":[{"futs_shrn_iscd":"101W12","hts_kor_isnm":"K200 선물 12월","hts_rmnn_dynu":"80"},
                {"futs_shrn_iscd":"101W09","hts_kor_isnm":"K200 선물 9월","hts_rmnn_dynu":"5"}]}""",
            )

            futures.fetchNearMonth(Market.KOSPI)!!.iscd shouldBe "101W09"
        }

        @Test
        fun `만기가 지난 종목은 제외한다`() {
            stub.respond(
                BOARD,
                """{"rt_cd":"0","output":[{"futs_shrn_iscd":"101W08","hts_rmnn_dynu":"-3"},{"futs_shrn_iscd":"101W09","hts_rmnn_dynu":"5"}]}""",
            )

            futures.fetchNearMonth(Market.KOSPI)!!.iscd shouldBe "101W09"
        }

        @Test
        fun `시장별로 전광판 코드가 갈린다`() {
            stub.server.stubFor(
                get(urlPathEqualTo(BOARD)).withQueryParam("FID_COND_MRKT_CLS_CODE", equalTo("KQI"))
                    .willReturn(okJson("""{"rt_cd":"0","output":[{"futs_shrn_iscd":"106W09","hts_rmnn_dynu":"5"}]}""")),
            )

            futures.fetchNearMonth(Market.KOSDAQ).shouldNotBeNull()
        }

        @Test
        fun `후보가 없으면 null`() {
            stub.respond(BOARD, """{"rt_cd":"0","output":[]}""")

            futures.fetchNearMonth(Market.KOSPI).shouldBeNull()
        }
    }

    @Nested
    inner class 선물_일봉 {
        @Test
        fun `베이시스는 선물빼기현물로 직접 낸다`() {
            stub.respond(
                DAILY,
                """{"rt_cd":"0","output1":{"hts_kor_isnm":"K200 선물","futs_prpr":"352.50","kospi200_nmix":"350.00",
                "futs_prdy_ctrt":"1.25","prdy_vrss_sign":"2","dprt":"0.71","hts_otst_stpl_qty":"300000","otst_stpl_qty_icdc":"1500"},
                "output2":[]}""",
            )

            val summary = futures.fetchDaily("101W09", from, to)!!.summary

            summary.basis shouldBe (2.5 plusOrMinus 1e-9)
            summary.changeRate shouldBe 1.25
        }

        @Test
        fun `하락 부호코드면 등락률이 음수가 된다`() {
            stub.respond(
                DAILY,
                """{"rt_cd":"0","output1":{"futs_prpr":"352.50","kospi200_nmix":"350.00","futs_prdy_ctrt":"1.25","prdy_vrss_sign":"5"},"output2":[]}""",
            )

            futures.fetchDaily("101W09", from, to)!!.summary.changeRate shouldBe -1.25
        }

        @Test
        fun `일봉은 시각이 자정으로 고정된다`() {
            stub.respond(
                DAILY,
                """{"rt_cd":"0","output1":{"futs_prpr":"352.50","kospi200_nmix":"350.00"},"output2":[{"stck_bsop_date":"20260911",
                "futs_prpr":"352.50","futs_oprc":"350.00","futs_hgpr":"353.00","futs_lwpr":"349.50","acml_vol":"120000"}]}""",
            )

            val bar = futures.fetchDaily("101W09", from, to)!!.candles[0]

            bar.at shouldBe LocalDateTime.of(2026, 9, 11, 0, 0)
        }

        @Test
        fun `현물이 없으면 null`() {
            stub.respond(DAILY, """{"rt_cd":"0","output1":{"futs_prpr":"352.50"}}""")

            futures.fetchDaily("101W09", from, to).shouldBeNull()
        }
    }

    @Nested
    inner class 선물_분봉 {
        @Test
        fun `야간의 24시 이상 시각은 익일로 되돌린다`() {
            stub.respond(
                MINUTE,
                """{"rt_cd":"0","output2":[{"stck_bsop_date":"20260911","stck_cntg_hour":"253000","futs_prpr":"352.50",
                "futs_oprc":"352.00","futs_hgpr":"353.00","futs_lwpr":"351.50","cntg_vol":"120"}]}""",
            )

            val bar = futures.fetchMinute("101W09", to, "253000", FuturesSession.NIGHT)[0]

            bar.at shouldBe LocalDateTime.of(2026, 9, 12, 1, 30)
        }

        @Test
        fun `정규장 시각은 그대로 둔다`() {
            stub.respond(MINUTE, """{"rt_cd":"0","output2":[{"stck_bsop_date":"20260911","stck_cntg_hour":"143000","futs_prpr":"352.50"}]}""")

            val bar = futures.fetchMinute("101W09", to, LocalTime.of(14, 30))[0]

            bar.at shouldBe LocalDateTime.of(2026, 9, 11, 14, 30)
        }

        @Test
        fun `시각 오름차순으로 정렬한다`() {
            stub.respond(
                MINUTE,
                """{"rt_cd":"0","output2":[{"stck_bsop_date":"20260911","stck_cntg_hour":"143000","futs_prpr":"1"},
                {"stck_bsop_date":"20260911","stck_cntg_hour":"090000","futs_prpr":"1"}]}""",
            )

            futures.fetchMinute("101W09", to, LocalTime.of(14, 30)).map { it.at.toLocalTime() } shouldBe listOf(LocalTime.of(9, 0), LocalTime.of(14, 30))
        }

        @Test
        fun `오류면 빈 목록`() {
            stub.respond(MINUTE, """{"rt_cd":"1","msg1":"오류"}""")

            futures.fetchMinute("101W09", to, LocalTime.of(14, 30)).shouldBeEmpty()
        }
    }

    @Nested
    inner class 선물_시세 {
        @Test
        fun `전일종가는 전일대비로 역산한다`() {
            stub.respond(
                PRICE,
                """{"rt_cd":"0","output1":{"hts_kor_isnm":"K200 선물","futs_prpr":"352.50","futs_prdy_vrss":"2.50",
                "futs_prdy_ctrt":"0.71","prdy_vrss_sign":"2","acml_vol":"120000"}}""",
            )

            val price = futures.fetchPrice("101W09", FuturesSession.NIGHT)!!

            price.priceChange shouldBe 2.50
            price.prevClose shouldBe (350.00 plusOrMinus 1e-9)
        }

        @Test
        fun `OHLC가 없으면 현재가로 채운다`() {
            stub.respond(PRICE, """{"rt_cd":"0","output1":{"futs_prpr":"352.50","futs_prdy_vrss":"0","prdy_vrss_sign":"3"}}""")

            val price = futures.fetchPrice("101W09", FuturesSession.DAY)!!

            listOf(price.open, price.high, price.low) shouldBe listOf(352.50, 352.50, 352.50)
        }

        @Test
        fun `output1이 없으면 null`() {
            stub.respond(PRICE, """{"rt_cd":"0"}""")

            futures.fetchPrice("101W09", FuturesSession.DAY).shouldBeNull()
        }
    }

    @Nested
    inner class 선물_투자자 {
        @Test
        fun `투자자별 계약 순매수를 읽는다`() {
            stub.respond(
                INVESTOR,
                """{"rt_cd":"0","output":[{"frgn_ntby_qty":"1500","prsn_ntby_qty":"-800","orgn_ntby_qty":"-700",
                "scrt_ntby_qty":"-300","pe_fund_ntby_vol":"-100","etc_corp_ntby_vol":"50"}]}""",
            )

            val result = futures.fetchInvestors(Market.KOSPI)!!

            listOf(result.foreign, result.individual, result.institution) shouldBe listOf(1500L, -800L, -700L)
            (result.privateEquity to result.otherCorp) shouldBe (-100L to 50L)
        }

        @Test
        fun `시장별로 업종코드가 갈린다`() {
            stub.server.stubFor(
                get(urlPathEqualTo(INVESTOR))
                    .withQueryParam("fid_input_iscd", equalTo("KQI"))
                    .withQueryParam("fid_input_iscd_2", equalTo("F002"))
                    .willReturn(okJson("""{"rt_cd":"0","output":[{"frgn_ntby_qty":"1"}]}""")),
            )

            futures.fetchInvestors(Market.KOSDAQ).shouldNotBeNull()
        }

        @Test
        fun `행이 없으면 null`() {
            stub.respond(INVESTOR, """{"rt_cd":"0","output":[]}""")

            futures.fetchInvestors(Market.KOSPI).shouldBeNull()
        }
    }

    companion object {
        private const val BOARD = "/uapi/domestic-futureoption/v1/quotations/display-board-futures"
        private const val DAILY = "/uapi/domestic-futureoption/v1/quotations/inquire-daily-fuopchartprice"
        private const val MINUTE = "/uapi/domestic-futureoption/v1/quotations/inquire-time-fuopchartprice"
        private const val PRICE = "/uapi/domestic-futureoption/v1/quotations/inquire-price"
        private const val INVESTOR = "/uapi/domestic-stock/v1/quotations/inquire-investor-time-by-market"
    }
}
