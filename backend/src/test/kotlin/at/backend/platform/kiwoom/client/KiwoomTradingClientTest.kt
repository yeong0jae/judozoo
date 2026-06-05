package at.backend.platform.kiwoom.client

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalTime

class KiwoomTradingClientTest : FunSpec({

    context("주문 거래소 구분 시간대 분기") {
        test("정규 접속매매 시간(09:00~15:30)에는 설정된 거래소를 그대로 사용한다") {
            KiwoomTradingClient.resolveStex(LocalTime.of(9, 0), "SOR") shouldBe "SOR"
            KiwoomTradingClient.resolveStex(LocalTime.of(12, 0), "SOR") shouldBe "SOR"
            KiwoomTradingClient.resolveStex(LocalTime.of(15, 29), "SOR") shouldBe "SOR"
        }

        test("정규장 마감(15:30) 이후 애프터마켓에는 NXT 단독으로 보낸다") {
            KiwoomTradingClient.resolveStex(LocalTime.of(15, 30), "SOR") shouldBe "NXT"
            KiwoomTradingClient.resolveStex(LocalTime.of(18, 45), "SOR") shouldBe "NXT"
            KiwoomTradingClient.resolveStex(LocalTime.of(19, 59), "SOR") shouldBe "NXT"
        }

        test("정규장 개장(09:00) 이전 프리마켓에는 NXT 단독으로 보낸다") {
            KiwoomTradingClient.resolveStex(LocalTime.of(8, 0), "SOR") shouldBe "NXT"
            KiwoomTradingClient.resolveStex(LocalTime.of(8, 59), "SOR") shouldBe "NXT"
        }
    }
})
