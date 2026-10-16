package judozoo.leadingstock.domain

data class SignalState(val spiking: Boolean = false) {
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
