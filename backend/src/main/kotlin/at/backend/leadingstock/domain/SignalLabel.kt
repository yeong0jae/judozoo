package at.backend.leadingstock.domain

import at.backend.library.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * 시그널 1건의 사후 결과(라벨). [SignalEvent]와 1:1 — signal_event_id를 그대로 PK로 쓴다.
 * 라벨링은 멱등 — 장중에 부분 채웠다가 마감 후 다시 라벨링하면 같은 행을 갱신한다.
 * 수익률(%)들의 의미는 [SignalLabelResult] 참고. 측정 불가 항목은 null.
 */
@Entity
@Table(name = "signal_label")
class SignalLabel(

    @Id
    @Column(name = "signal_event_id", nullable = false)
    val signalEventId: Long,

    /** 기준가 = 신호 순간 [SignalEvent.currentPrice]. */
    @Column(name = "entry_price", nullable = false)
    val entryPrice: Long,

    @Column var ret1m: Double? = null,
    @Column var ret2m: Double? = null,
    @Column var ret20m: Double? = null,
    @Column var ret2h: Double? = null,
    @Column var retClose: Double? = null,
    @Column var mfe: Double? = null,
    @Column var mae: Double? = null,
) : BaseEntity() {

    /** 멱등 재라벨링 — 같은 행에 새 결과를 덮어쓴다. */
    fun apply(result: SignalLabelResult) {
        ret1m = result.ret1m
        ret2m = result.ret2m
        ret20m = result.ret20m
        ret2h = result.ret2h
        retClose = result.retClose
        mfe = result.mfe
        mae = result.mae
    }

    companion object {
        fun of(signalEventId: Long, entryPrice: Long, result: SignalLabelResult) =
            SignalLabel(signalEventId, entryPrice).apply { apply(result) }
    }
}
