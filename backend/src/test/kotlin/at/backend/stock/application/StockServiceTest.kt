package at.backend.stock.application

import at.backend.common.test.FixedTimeProviderConfig
import at.backend.common.test.IntegrationTestBase
import at.backend.common.test.KisRestClientMockConfig
import at.backend.common.test.MutableTimeProvider
import at.backend.platform.kis.client.KisRestClient
import at.backend.platform.kis.client.response.KisCurrentPriceResponse
import at.backend.platform.kis.client.response.KisStockSearchResponse
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.time.LocalDateTime

@Import(KisRestClientMockConfig::class, FixedTimeProviderConfig::class)
class StockServiceTest(
    @Autowired private val stockService: StockService,
    @Autowired private val kisRestClient: KisRestClient,
    @Autowired private val timeProvider: MutableTimeProvider,
) : IntegrationTestBase() {

    init {
        beforeEach {
            clearMocks(kisRestClient)
            timeProvider.current = FixedTimeProviderConfig.DEFAULT_NOW
        }

        context("종목 검색") {
            test("KIS 응답을 종목코드/종목명으로 매핑한다") {
                every { kisRestClient.searchStock("삼성") } returns KisStockSearchResponse(
                    output = listOf(
                        KisStockSearchResponse.Output(pdno = "005930", prdtAbrvName = "삼성전자"),
                        KisStockSearchResponse.Output(pdno = "207940", prdtAbrvName = "삼성바이오로직스"),
                    )
                )

                val result = stockService.search("삼성")

                result shouldHaveSize 2
                result[0].stockCode shouldBe "005930"
                result[0].stockName shouldBe "삼성전자"
                result[1].stockCode shouldBe "207940"
                result[1].stockName shouldBe "삼성바이오로직스"
            }

            test("KIS 검색 결과가 비어있으면 빈 목록을 반환한다") {
                every { kisRestClient.searchStock(any()) } returns KisStockSearchResponse(output = emptyList())

                val result = stockService.search("없는종목")

                result shouldBe emptyList()
            }
        }

        context("현재가 조회") {
            test("KIS 응답의 현재가를 Long으로 변환해 반환한다") {
                every { kisRestClient.getCurrentPrice("005930") } returns KisCurrentPriceResponse(
                    output = KisCurrentPriceResponse.Output(stckPrpr = "70000")
                )

                val result = stockService.getPrice("005930")

                result.stockCode shouldBe "005930"
                result.currentPrice shouldBe 70_000L
            }

            test("asOf는 TimeProvider가 제공하는 현재 시각이다") {
                every { kisRestClient.getCurrentPrice(any()) } returns KisCurrentPriceResponse(
                    output = KisCurrentPriceResponse.Output(stckPrpr = "70000")
                )
                val fixedNow = LocalDateTime.of(2026, 3, 14, 9, 30)
                timeProvider.current = fixedNow

                val result = stockService.getPrice("005930")

                result.asOf shouldBe fixedNow
            }
        }
    }
}
