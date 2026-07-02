package at.backend.overseasleadingstock.domain

import at.backend.leadingstock.domain.SignalEventType

/**
 * 해외 종목 한 폴링 시점의 시그널 측정값. 값 없으면 null.
 * [gapRate]: 전고점까지 남은 상승률(%), 돌파 시 0 이하. [peakPrice]: 그 시점 누적 전고점(달러).
 * [spikeRatio]: 최신 1분봉 거래대금 배율 — 거래대금 임계 미달이면 null.
 * [aboveMa20]: 직전 확정 5분봉 종가가 5분봉 20이평 위인지 — 확정 봉 부족이면 null.
 */
data class OverseasSignalReading(
    val gapRate: Double?,
    val peakPrice: Double?,
    val spikeRatio: Double?,
    val aboveMa20: Boolean?,
)

/**
 * 해외 종목 직전 시그널 상태. 국내 SignalState와 동일한 히스테리시스 — 가격만 달러(Double).
 * 돌파/임박: 진입·해제 임계 분리. 돌파 재인정: 직전 돌파보다 높은 전고점일 때만.
 */
class OverseasSignalState private constructor(
    private val broken: Boolean,
    private val imminent: Boolean,
    private val lastBrokenPeak: Double,
    private val spiking: Boolean,
    private val aboveMa20: Boolean?,
) {
    fun advance(reading: OverseasSignalReading): Pair<List<SignalEventType>, OverseasSignalState> {
        val events = mutableListOf<SignalEventType>()
        var broken = broken
        var imminent = imminent
        var lastBrokenPeak = lastBrokenPeak
        var spiking = spiking
        var aboveMa20 = aboveMa20

        val gap = reading.gapRate
        val peak = reading.peakPrice
        if (gap != null && peak != null) {
            if (!broken && gap <= BROKEN_GAP) {
                if (peak > lastBrokenPeak) {
                    events += SignalEventType.BREAKOUT
                    lastBrokenPeak = peak
                }
                broken = true
                imminent = false
            } else if (broken && gap >= BROKEN_RESET_GAP) {
                broken = false
            }

            if (!broken) {
                if (!imminent && gap > BROKEN_GAP && gap < IMMINENT_GAP) {
                    events += SignalEventType.BREAKOUT_IMMINENT
                    imminent = true
                } else if (imminent && gap >= IMMINENT_RESET_GAP) {
                    imminent = false
                }
            }
        }

        val ratio = reading.spikeRatio
        if (!spiking && ratio != null && ratio >= SPIKE_FIRE_RATIO) {
            events += SignalEventType.VOLUME_SPIKE
            spiking = true
        } else if (spiking && (ratio == null || ratio < SPIKE_RESET_RATIO)) {
            spiking = false
        }

        // 돌림: 직전 확정 5분봉 종가가 20이평을 아래→위로 처음 뚫는 순간. 위치가 바뀌는 라이징 엣지만.
        // 최초 관측(null→위)은 실제 돌파를 못 본 것이라 발화하지 않고 위치만 기록한다.
        val above = reading.aboveMa20
        if (above != null) {
            if (aboveMa20 == false && above) events += SignalEventType.MA20_CROSS
            aboveMa20 = above
        }

        return events to OverseasSignalState(broken, imminent, lastBrokenPeak, spiking, aboveMa20)
    }

    companion object {
        val INITIAL = OverseasSignalState(
            broken = false, imminent = false, lastBrokenPeak = 0.0, spiking = false, aboveMa20 = null,
        )

        private const val BROKEN_GAP = 0.0
        private const val BROKEN_RESET_GAP = 0.5
        private const val IMMINENT_GAP = 2.0
        private const val IMMINENT_RESET_GAP = 2.5
        private const val SPIKE_FIRE_RATIO = 2.5
        private const val SPIKE_RESET_RATIO = 2.0
    }
}
