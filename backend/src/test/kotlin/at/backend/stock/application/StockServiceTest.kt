package at.backend.stock.application

import at.backend.common.test.FixedTimeProviderConfig
import at.backend.common.test.IntegrationTestBase
import at.backend.common.test.KisStockMasterClientMockConfig
import at.backend.common.test.MutableTimeProvider
import at.backend.platform.kis.client.KisRestClient
import at.backend.platform.kis.client.response.KisCurrentPriceResponse
import at.backend.stock.domain.Market
import at.backend.stock.domain.Stock
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.time.LocalDateTime

@Import(
    KisStockMasterClientMockConfig::class,
    FixedTimeProviderConfig::class,
)
class StockServiceTest(
    @Autowired private val stockService: StockService,
    @Autowired private val kisRestClient: KisRestClient,
    @Autowired private val stockCatalog: StockCatalog,
    @Autowired private val timeProvider: MutableTimeProvider,
) : IntegrationTestBase() {

    init {
        beforeEach {
            clearMocks(kisRestClient)
            timeProvider.current = FixedTimeProviderConfig.DEFAULT_NOW
        }

        context("종목 검색") {
            test("카탈로그에서 종목명/코드로 찾아 코드·이름으로 매핑한다") {
                stockCatalog.replace(
                    listOf(
                        Stock("005930", "KR7005930003", "삼성전자", Market.KOSPI),
                        Stock("207940", "KR7207940008", "삼성바이오로직스", Market.KOSPI),
                    ),
                )

                val result = stockService.search("삼성전자")

                result shouldHaveSize 1
                result[0].stockCode shouldBe "005930"
                result[0].stockName shouldBe "삼성전자"
            }

            test("일치하는 종목이 없으면 빈 결과를 반환한다") {
                stockCatalog.replace(listOf(Stock("005930", "KR7005930003", "삼성전자", Market.KOSPI)))

                stockService.search("없는종목") shouldHaveSize 0
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
