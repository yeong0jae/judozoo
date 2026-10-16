package judozoo.leadertimeline.domain

import io.kotest.matchers.shouldBe
import judozoo.market.domain.Region
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalTime

class CaptureWindowTest {
    private fun kr(at: String) = CaptureWindow.of(Region.KR).captures(LocalTime.parse(at))

    @Nested
    inner class 국내_찍는_분 {
        @ParameterizedTest
        @ValueSource(strings = ["08:01", "08:49", "09:00", "15:29", "15:40", "19:59", "20:00"])
        fun `세션 안은 마감 분까지 찍는다`(at: String) {
            kr(at) shouldBe true
        }

        @ParameterizedTest
        @ValueSource(strings = ["08:50", "08:59", "15:30", "15:39"])
        fun `장 사이 쉬는 구간은 찍지 않는다`(at: String) {
            kr(at) shouldBe false
        }

        @ParameterizedTest
        @ValueSource(strings = ["07:59", "08:00", "20:01", "23:00"])
        fun `세션 밖은 찍지 않는다`(at: String) {
            kr(at) shouldBe false
        }
    }

    @Nested
    inner class 해외_찍는_분 {
        private val us = CaptureWindow.of(Region.US)

        @Test
        fun `프리마켓부터 애프터마켓까지 쉬지 않고 마감 분까지 이어진다`() {
            (4 until 20).all { h -> (0 until 60).all { m -> us.captures(LocalTime.of(h, m)) } } shouldBe true
            us.captures(LocalTime.of(20, 0)) shouldBe true
        }

        @ParameterizedTest
        @ValueSource(strings = ["03:59", "20:01"])
        fun `세션 밖은 찍지 않는다`(at: String) {
            us.captures(LocalTime.parse(at)) shouldBe false
        }
    }
}
