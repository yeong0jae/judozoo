package judozoo.leadingstock.domain

/**
 * 한 종목의 직전 시그널 상태. 전이는 **상태가 바뀌는 첫 순간에만** 한 번 잡는다(라이징 엣지).
 *
 * 임계선 근처 떨림은 히스테리시스로 흡수한다 — 한 번 켜지면 해제 임계 아래로 식어야 다시 켜진다.
 */
data class SignalState(val spiking: Boolean = false) {

    /** 새 측정값(최신 1분봉 거래대금 배율, 임계 미달이면 null)을 받아 이번에 발생한 전이와 다음 상태를 돌려준다. */
    fun advance(spikeRatio: Double?): Pair<List<SignalEventType>, SignalState> = when {
        !spiking && spikeRatio != null && spikeRatio >= SPIKE_FIRE_RATIO -> listOf(SignalEventType.VOLUME_SPIKE) to SignalState(true)
        spiking && (spikeRatio == null || spikeRatio < SPIKE_RESET_RATIO) -> emptyList<SignalEventType>() to SignalState(false)
        else -> emptyList<SignalEventType>() to this
    }

    companion object {
        private const val SPIKE_FIRE_RATIO = 2.5
        private const val SPIKE_RESET_RATIO = 2.0
    }
}
