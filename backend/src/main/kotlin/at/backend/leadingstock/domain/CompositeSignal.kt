package at.backend.leadingstock.domain

/**
 * 한 종목의 돌파·스파이크 시그널 교차. 두 신호가 함께 켜질수록 강한 자리로 본다.
 *
 * [breakout]은 돌파선까지 갭이 [breakoutNearGapRate]% 미만(근접 이상)일 때만 "켜진" 것으로 인정한다.
 * 갭이 그보다 크면(관망) null로 떨군다. [spike]는 이미 배율·거래대금 임계를 통과한 값만 들어오므로
 * 존재 자체가 켜진 신호다.
 */
class CompositeSignal(
    breakout: SwingHighSignal?,
    spike: VolumeSpike?,
    breakoutNearGapRate: Double,
) {
    /** 근접(갭 < 임계) 또는 돌파한 돌파선 신호만 노출. 관망 거리면 null. */
    val breakout: SwingHighSignal? = breakout?.takeIf { it.gapRate < breakoutNearGapRate }

    /** 임계 통과분만 들어오므로 그대로 노출. */
    val spike: VolumeSpike? = spike

    /** 켜진 신호 수 (0~2). 클수록 교차가 강하다. */
    val signalCount: Int =
        (if (this.breakout != null) 1 else 0) + (if (this.spike != null) 1 else 0)
}
