package judozoo.auth.presentation

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class GateTest {
    @Nested
    inner class 로그인_없이_열어둔_경로 {
        @ParameterizedTest
        @ValueSource(
            strings = [
                "/api/leading-stocks/candidates",
                "/api/leading-stocks/leaders",
                "/api/overseas-leading-stocks/leaders",
                "/api/leading-stocks/signal-events",
                "/api/leading-stocks/breakout-radar",
                "/api/overseas-leading-stocks/candidates",
                "/api/market/calendar/status",
                "/api/market/kospi",
                "/api/market/kosdaq",
                "/api/market/nasdaq/quote",
                "/api/market/macro/quotes",
                "/api/market/futures/night/quote",
                "/api/market/futures/night/candles",
                "/api/market/KOSPI/candles",
                "/api/market/futures/KOSPI/quote",
                "/api/market/futures/KOSDAQ/candles",
                "/api/market/futures/nasdaq/quote",
                "/api/market/futures/nasdaq/candles",
                "/api/market/nasdaq/candles",
                "/api/market/macro/candles",
                "/api/auth/google/callback",
            ],
        )
        fun `주도주 목록과 공개 지표는 누구나 본다`(path: String) {
            Gate.isPublic(path) shouldBe true
        }

        @ParameterizedTest
        @ValueSource(
            strings = [
                "/api/market/kospi/",
                "/api/leading-stocks/candidates/",
                "/api/overseas-leading-stocks/candidates/",
                "/api/auth/google/callback/",
            ],
        )
        fun `끝의 빗금은 같은 경로로 본다`(path: String) {
            Gate.isPublic(path) shouldBe true
        }
    }

    @Nested
    inner class 로그인을_요구하는_경로 {
        @ParameterizedTest
        @ValueSource(
            strings = [
                "/api/leading-stocks/candidates/005930",
                "/api/leading-stocks/candidates/005930/minute-candles",
                "/api/overseas-leading-stocks/NAS/NVDA",
                "/api/overseas-leading-stocks/NAS/NVDA/daily-candles",
                "/api/market/KOSPI/investor/sessions",
                "/api/market/KOSPI/investor/daily",
                "/api/market/KOSDAQ/investor/sessions",
                "/api/market/KOSDAQ/investor/daily",
                "/api/market/futures/KOSPI/investor/sessions",
                "/api/market/futures/KOSPI/investor/daily",
                "/api/market/futures/KOSDAQ/investor/sessions",
                "/api/market/futures/KOSDAQ/investor/daily",
                "/api/market/investor/today",
                "/api/stocks/005930/investor/daily",
                "/api/leading-stocks/market-signal-events",
                "/api/leading-stocks/market/investor-net-buy",
            ],
        )
        fun `상세와 가공 결과는 막힌다`(path: String) {
            Gate.isPublic(path) shouldBe false
        }

        @Test
        fun `허용목록에 없는 새 경로는 기본이 차단이다`() {
            Gate.isPublic("/api/leading-stocks/whatever-comes-next") shouldBe false
        }

        @Test
        fun `공개 경로를 앞에 붙여도 뚫리지 않는다`() {
            Gate.isPublic("/api/market/kospi/../futures/kospi/quote") shouldBe false
        }
    }
}
