package at.backend.market.application

import at.backend.common.test.IntegrationTestBase
import at.backend.common.test.KisRestClientMockConfig
import at.backend.platform.kis.client.KisRestClient
import at.backend.platform.kis.client.response.KisBarResponse
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource

@Import(KisRestClientMockConfig::class)
@TestPropertySource(properties = ["trading.market.bar-poll-interval-millis=30"])
class BarPollerTest(
    @Autowired private val barPoller: BarPoller,
    @Autowired private val restClient: KisRestClient,
) : IntegrationTestBase() {

    init {
        beforeEach {
            barPoller.reset()
            clearMocks(restClient, answers = false)
        }

        context("새 봉 닫힘 감지") {
            test("폴링 결과가 처음 들어올 때 Bar 이벤트가 발행된다") {
                every { restClient.getBars("005930") } returns barResponse("20260504", "150300", 70_000, 70_500)

                val bar = coroutineScope {
                    val deferred = async {
                        withTimeout(2000) { barPoller.bars.first() }
                    }
                    delay(100)
                    barPoller.subscribe("005930")
                    deferred.await()
                }
                bar.stockCode shouldBe "005930"
                bar.openPrice shouldBe 70_000
                bar.closePrice shouldBe 70_500
            }

            test("동일 봉 시각이면 다시 발행되지 않는다") {
                every { restClient.getBars("005930") } returns barResponse("20260504", "150300", 70_000, 70_500)

                coroutineScope {
                    val deferred = async {
                        withTimeout(2000) { barPoller.bars.first() }
                    }
                    delay(100)
                    barPoller.subscribe("005930")
                    deferred.await()
                }

                val received = withTimeoutOrNull(200) { barPoller.bars.first() }
                received shouldBe null
            }

            test("새 봉 시각으로 응답이 바뀌면 Bar가 한 번 더 발행된다") {
                every { restClient.getBars("005930") } returns barResponse("20260504", "150300", 70_000, 70_500)

                coroutineScope {
                    val deferred = async {
                        withTimeout(2000) { barPoller.bars.first() }
                    }
                    delay(100)
                    barPoller.subscribe("005930")
                    deferred.await()
                }

                val next = coroutineScope {
                    val deferred = async {
                        withTimeout(2000) { barPoller.bars.first() }
                    }
                    delay(100)
                    every { restClient.getBars("005930") } returns barResponse("20260504", "150600", 70_500, 70_700)
                    deferred.await()
                }
                next.closePrice shouldBe 70_700
            }
        }
    }

    /**
     * BarPoller는 output2[1] (직전 닫힌 봉)을 사용. [0]은 진행 중 봉이므로 더미로 채움.
     * 테스트 인자(date/time/open/close)는 [1]에 매핑된다.
     */
    private fun barResponse(date: String, time: String, open: Int, close: Int): KisBarResponse =
        KisBarResponse(
            output2 = listOf(
                KisBarResponse.Output(
                    stckBsopDate = date, stckCntgHour = "153000", stckOprc = "1", stckPrpr = "1",
                ),
                KisBarResponse.Output(
                    stckBsopDate = date,
                    stckCntgHour = time,
                    stckOprc = open.toString(),
                    stckPrpr = close.toString(),
                ),
            )
        )
}
