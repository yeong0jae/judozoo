package at.backend.market.domain.regime

import at.backend.library.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate

/**
 * 하루치 시장 흐름 결과 — 멀티데이(최근 N일) 비교용.
 *
 * - [gap1]: 전일 종가 대비 아침 NXT(08:15) 갭.
 * - [gap2Close]: 아침 NXT 대비 본장 종가.
 * - [gap2High]/[gap2Low]: 본장 중 최고/최저 (장중 출렁임).
 *
 * 본장 동안 매 폴마다 [update]로 갱신한다(종가=최신, 고/저 누적).
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

    /** 10:00 시점 본장 갭(아침 NXT 대비) — 오전장 구간 산출용. 10:00 전이면 null. */
    @Column
    var gap2At1000: Double? = null,
) : BaseEntity() {

    /** 본장 갱신 — 종가=최신, 고/저 누적, 10:00 통과 시 중간값 1회 고정. */
    fun update(gap1: Double, gap2: Double, captureMidpoint: Boolean) {
        this.gap1 = gap1
        this.gap2Close = gap2
        if (gap2 > gap2High) gap2High = gap2
        if (gap2 < gap2Low) gap2Low = gap2
        if (captureMidpoint && gap2At1000 == null) gap2At1000 = gap2
    }
}
