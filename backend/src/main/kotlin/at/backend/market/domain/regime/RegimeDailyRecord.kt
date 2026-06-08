package at.backend.market.domain.regime

import at.backend.library.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.LocalTime

/**
 * 하루치 시장 흐름 결과 — 멀티데이(최근 N일) 비교용.
 *
 * 모든 gap2* 는 오전 NXT(08:15) 대비 값이며, 해당 시각을 지나면 1회 고정한다.
 * - [gap1]: 전일 종가 대비 오전 NXT(08:15)
 * - [gap2At1000]/[gap2At1530]/[gap2At2000]: 08:15 대비 10:00 / 15:30(KRX 마감) / 20:00(NXT 애프터마켓)
 * - [gap2Close]: 08:15 대비 최신값(라이브) / [gap2High],[gap2Low]: 장중 최고·최저
 */
@Entity
@Table(name = "regime_daily")
class RegimeDailyRecord(

    @Id
    @Column(nullable = false)
    val date: LocalDate,

    @Column(nullable = false)
    var gap1: Double,

    @Column(nullable = false)
    var gap2Close: Double,

    @Column(nullable = false)
    var gap2High: Double,

    @Column(nullable = false)
    var gap2Low: Double,

    @Column
    var gap2At1000: Double? = null,

    @Column
    var gap2At1530: Double? = null,

    @Column
    var gap2At2000: Double? = null,
) : BaseEntity() {

    /** 매 폴 갱신 — 종가=최신, 고/저 누적, 각 시각(10:00·15:30·20:00) 통과 시 1회 고정. */
    fun update(gap1: Double, gap2: Double, now: LocalTime) {
        this.gap1 = gap1
        this.gap2Close = gap2
        if (gap2 > gap2High) gap2High = gap2
        if (gap2 < gap2Low) gap2Low = gap2
        if (now >= AT_1000 && gap2At1000 == null) gap2At1000 = gap2
        if (now >= AT_1530 && gap2At1530 == null) gap2At1530 = gap2
        if (now >= AT_2000 && gap2At2000 == null) gap2At2000 = gap2
    }

    companion object {
        private val AT_1000 = LocalTime.of(10, 0)
        private val AT_1530 = LocalTime.of(15, 30)
        private val AT_2000 = LocalTime.of(20, 0)
    }
}
