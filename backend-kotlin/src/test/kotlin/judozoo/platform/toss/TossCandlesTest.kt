package judozoo.platform.toss

import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldBeSorted
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class TossCandlesTest {
    private val stub = TossStub()
    private val today = LocalDate.of(2026, 9, 28)

    // KST 2026-09-28 10:00
    private val candles = TossCandles(stub.client, Clock.fixed(Instant.parse("2026-09-28T01:00:00Z"), ZoneOffset.UTC))

    @AfterEach
    fun 정리() = stub.close()

    /** 토스 모양 그대로 — 시각은 봉이 끝나는 시각이다. */
    private fun bar(end: String, close: Int = 100, volume: Int = 10) =
        """{"timestamp":"$end+09:00","openPrice":"99","highPrice":"101","lowPrice":"98","closePrice":"$close","volume":"$volume","currency":"KRW"}"""

    private fun page(vararg bars: String, nextBefore: String? = null) =
        """{"result":{"candles":[${bars.joinToString(",")}],"nextBefore":${nextBefore?.let { "\"$it\"" }}}}"""

    @Nested
    inner class 키움_모양으로_맞추기 {
        @Test
        fun `봉 끝 시각을 1분 당겨 봉 시작 시각으로 둔다`() {
            stub.respond(URL, page(bar("2026-09-28T09:01:00")))

            candles.fetchTodayMinuteCandles("005930").single().dateTime shouldBe LocalDateTime.of(2026, 9, 28, 9, 0)
        }

        /** 두면 스파이크의 직전 평균과 이평이 키움 때와 달라진다. */
        @Test
        fun `체결 없는 빈 봉은 버린다`() {
            stub.respond(URL, page(bar("2026-09-28T09:02:00"), bar("2026-09-28T09:01:00", volume = 0)))

            candles.fetchTodayMinuteCandles("005930").map { it.dateTime.minute } shouldBe listOf(1)
        }

        @Test
        fun `시각 오름차순으로 돌려준다`() {
            stub.respond(URL, page(bar("2026-09-28T09:03:00"), bar("2026-09-28T09:02:00"), bar("2026-09-28T09:01:00")))

            candles.fetchTodayMinuteCandles("005930").map { it.dateTime }.shouldBeSorted()
        }

        @Test
        fun `거래대금은 종가에 거래량을 곱해 근사한다`() {
            stub.respond(URL, page(bar("2026-09-28T09:01:00", close = 200, volume = 3)))

            candles.fetchTodayMinuteCandles("005930")[0].tradingValue shouldBe 600
        }

        @Test
        fun `랭킹 코드의 SOR 접미사를 떼고 부른다`() {
            stub.respond(URL, page())

            candles.fetchTodayMinuteCandles("005930_AL")

            stub.server.verify(getRequestedFor(urlPathEqualTo(URL)).withQueryParam("symbol", equalTo("005930")))
        }
    }

    @Nested
    inner class 하루치_받기 {
        @Test
        fun `전날 봉이 나올 때까지 거꾸로 넘긴다`() {
            stub.respondInOrder(
                URL,
                200 to page(bar("2026-09-28T09:02:00"), nextBefore = "2026-09-28T09:01:00+09:00"),
                200 to page(bar("2026-09-28T09:01:00"), bar("2026-09-25T20:00:00"), nextBefore = "2026-09-25T19:59:00+09:00"),
            )

            val result = candles.fetchTodayMinuteCandles("005930")

            stub.calls(URL) shouldBe 2
            result.map { it.dateTime.toLocalDate() } shouldBe listOf(today, today) // 전날 봉은 빠진다
        }

        @Test
        fun `장 시작 전이면 빈 목록이다`() {
            stub.respond(URL, page(bar("2026-09-25T20:00:00"), nextBefore = "2026-09-25T19:59:00+09:00"))

            candles.fetchTodayMinuteCandles("005930").shouldBeEmpty()
        }
    }

    @Nested
    inner class 이어_받기 {
        @Test
        fun `기준 시각 이후 봉만 몇 개 청해서 받는다`() {
            stub.respond(
                URL,
                page(bar("2026-09-28T10:00:00"), bar("2026-09-28T09:59:00"), bar("2026-09-28T09:58:00"), nextBefore = "2026-09-28T09:57:00+09:00"),
            )

            val result = candles.fetchTodayMinuteCandles("005930", since = LocalDateTime.of(2026, 9, 28, 9, 58))

            stub.calls(URL) shouldBe 1
            stub.requests(URL)[0].queryParameter("count").firstValue().toInt() shouldBeLessThan 200
            result.map { it.dateTime.minute } shouldBe listOf(58, 59)
        }
    }

    @Nested
    inner class 실패 {
        @Test
        fun `한도 초과면 잠깐 쉬고 한 번 더 부른다`() {
            stub.respondInOrder(URL, 429 to "{}", 200 to page(bar("2026-09-28T09:01:00")))

            val result = candles.fetchTodayMinuteCandles("005930")

            stub.calls(URL) shouldBe 2
            stub.pauses shouldBe listOf(Duration.ofSeconds(1))
            result shouldHaveSize 1
        }

        /** 한 번만 다시 부른다 — 계속 넘치면 부르는 쪽(다음 갱신 회차)에 맡긴다. */
        @Test
        fun `다시 불러도 한도 초과면 예외로 올린다`() {
            stub.respond(URL, "{}", status = 429)

            shouldThrow<HttpClientErrorException.TooManyRequests> { candles.fetchTodayMinuteCandles("005930") }
            stub.calls(URL) shouldBe 2
        }

        /** 이어 붙이는 쪽이 기존 봉을 지키려면 실패를 알아야 한다. 한도 초과가 아니면 다시 부르지 않는다. */
        @Test
        fun `한도 초과가 아닌 오류는 다시 부르지 않고 예외로 올린다`() {
            stub.respond(URL, "{}", status = 500)

            shouldThrow<HttpServerErrorException> { candles.fetchTodayMinuteCandles("005930") }
            stub.calls(URL) shouldBe 1
        }

        /** 과거 분봉(키움)이 수정주가라 오늘 봉도 같은 기준이어야 돌파선이 맞는다. */
        @Test
        fun `수정주가로 청한다`() {
            stub.respond(URL, page())

            candles.fetchTodayMinuteCandles("005930")

            stub.server.verify(getRequestedFor(urlPathEqualTo(URL)).withQueryParam("adjusted", equalTo("true")))
        }
    }

    companion object {
        private const val URL = "/api/v1/candles"
    }
}
