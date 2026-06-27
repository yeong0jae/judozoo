package at.backend.leadingstock.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalTime

class TimeBucketTest : FunSpec({

    context("시간대 분류") {
        test("정규장 경계(09:00·15:30)로 NXT 프리/애프터를 가른다") {
            TimeBucket.of(LocalTime.of(8, 30)) shouldBe TimeBucket.PRE_NXT
            TimeBucket.of(LocalTime.of(8, 59)) shouldBe TimeBucket.PRE_NXT
            TimeBucket.of(LocalTime.of(9, 0)) shouldBe TimeBucket.EARLY
            TimeBucket.of(LocalTime.of(15, 29)) shouldBe TimeBucket.LATE
            TimeBucket.of(LocalTime.of(15, 30)) shouldBe TimeBucket.POST_NXT
            TimeBucket.of(LocalTime.of(19, 0)) shouldBe TimeBucket.POST_NXT
        }

        test("장중 구간 경계(10:00·14:30)") {
            TimeBucket.of(LocalTime.of(9, 59)) shouldBe TimeBucket.EARLY
            TimeBucket.of(LocalTime.of(10, 0)) shouldBe TimeBucket.MID
            TimeBucket.of(LocalTime.of(14, 29)) shouldBe TimeBucket.MID
            TimeBucket.of(LocalTime.of(14, 30)) shouldBe TimeBucket.LATE
        }
    }
})
