package judozoo.auth

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
                // 지수·선물의 시세와 차트 — 수급만 로그인 뒤로 남긴다
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

        /** 허용목록 전체를 묶지 않으면 이 꼬리가 마지막 항목에만 걸린다. */
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
                // 목록은 열어도 종목 상세는 닫는다 — 국내·해외 모두
                "/api/leading-stocks/candidates/005930",
                "/api/leading-stocks/candidates/005930/minute-candles",
                "/api/overseas-leading-stocks/NAS/NVDA",
                "/api/overseas-leading-stocks/NAS/NVDA/daily-candles",
                // 시세·차트는 열었지만 투자자 수급은 닫은 채다 — 경로가 한 겹 차이라 섞이기 쉽다
                "/api/market/KOSPI/investor/sessions",
                "/api/market/KOSPI/investor/daily",
                "/api/market/KOSDAQ/investor/sessions",
                "/api/market/KOSDAQ/investor/daily",
                "/api/market/futures/KOSPI/investor/sessions",
                "/api/market/futures/KOSPI/investor/daily",
                "/api/market/futures/KOSDAQ/investor/sessions",
                "/api/market/futures/KOSDAQ/investor/daily",
                // 첫 화면 "오늘의 수급"도 수급이다 — 홈에 실린다고 열리지 않는다
                "/api/market/investor/today",
                // 종목별 수급도 종목 상세라 닫은 채다
                "/api/stocks/005930/investor/daily",
                // 가공한 판단 결과들 — 종목 시그널 미리보기만 열고 나머지는 닫는다
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
