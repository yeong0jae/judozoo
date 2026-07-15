package at.backend.leadingstock.domain

/**
 * 한 종목의 현재 시그널 측정값(한 폴링 시점). 값이 없으면(분봉 미존재 등) null.
 * [gapRate]: 전고점까지 남은 상승률(%), 돌파 시 0 이하. [peakPrice]: 그 시점 당일 전고점.
 * [spikeRatio]: 최신 1분봉 거래대금 배율 — 거래대금 임계 미달이면 null로 들어온다.
 * [ma20CrossedUp]: 최신 확정 5분봉이 20이평을 아래→위로 돌파한 봉인지 — 확정 봉 부족이면 null.
 * [ma20BelowBand]: 최신 확정 5분봉 종가가 20이평보다 마진 이상 아래인지 — 돌림 재무장 신호. null이면 확정 봉 부족.
 */
data class SignalReading(
    val gapRate: Double?,
    val peakPrice: Long?,
    val spikeRatio: Double?,
    val ma20CrossedUp: Boolean?,
    val ma20BelowBand: Boolean?,
)

/**
 * 한 종목의 직전 시그널 상태. [advance]로 새 측정값을 받아 "이번에 발생한 전이"와 다음 상태를 함께 돌려준다.
 *
 * 전이는 상태가 바뀌는 첫 순간에만 한 번 잡고(라이징 엣지), 임계선 근처 떨림은 히스테리시스로 흡수한다.
 * - 돌파/임박: 진입 임계와 해제 임계를 따로 둬, 한 번 켜지면 충분히 물러나야(reset) 다시 켜진다.
 * - 돌파 재인정: 해제 후 다시 돌파해도, 직전 돌파 때보다 **더 높은 전고점**을 깰 때만 새 이벤트로 본다(눌림 후 신고가 재돌파).
 */
class SignalState private constructor(
    private val broken: Boolean,
    private val imminent: Boolean,
    private val lastBrokenPeak: Long,
    private val spiking: Boolean,
) {
    fun advance(reading: SignalReading): Pair<List<SignalEventType>, SignalState> {
        val events = mutableListOf<SignalEventType>()
        var broken = broken
        var imminent = imminent
        var lastBrokenPeak = lastBrokenPeak
        var spiking = spiking

        val gap = reading.gapRate
        val peak = reading.peakPrice
        if (gap != null && peak != null) {
            // 돌파: 갭 0 이하 진입. 직전 돌파보다 높은 전고점일 때만 새 이벤트.
            if (!broken && gap <= BROKEN_GAP) {
                if (peak > lastBrokenPeak) {
                    events += SignalEventType.BREAKOUT
                    lastBrokenPeak = peak
                }
                broken = true
                imminent = false // 돌파했으면 임박 상태는 소거
            } else if (broken && gap >= BROKEN_RESET_GAP) {
                broken = false // 눌림 — 재무장
            }

            // 임박: 돌파 전(0 < 갭 < 임계) 접근. 돌파 상태에서는 보지 않는다.
            if (!broken) {
                if (!imminent && gap > BROKEN_GAP && gap < IMMINENT_GAP) {
                    events += SignalEventType.BREAKOUT_IMMINENT
                    imminent = true
                } else if (imminent && gap >= IMMINENT_RESET_GAP) {
                    imminent = false
                }
            }
        }

        // 스파이크: 배율 2.5배 진입 시 발화, 2배 아래로 식으면 해제.
        val ratio = reading.spikeRatio
        if (!spiking && ratio != null && ratio >= SPIKE_FIRE_RATIO) {
            events += SignalEventType.VOLUME_SPIKE
            spiking = true
        } else if (spiking && (ratio == null || ratio < SPIKE_RESET_RATIO)) {
            spiking = false
        }

        return events to SignalState(broken, imminent, lastBrokenPeak, spiking)
    }

    companion object {
        val INITIAL = SignalState(
            broken = false, imminent = false, lastBrokenPeak = 0, spiking = false,
        )

        private const val BROKEN_GAP = 0.0
        private const val BROKEN_RESET_GAP = 0.5   // 돌파 해제(재무장) 기준
        private const val IMMINENT_GAP = 2.0       // 임박 진입 기준(화면 임박 띠 0~2%와 일치)
        private const val IMMINENT_RESET_GAP = 2.5 // 임박 해제 기준
        private const val SPIKE_FIRE_RATIO = 2.5
        private const val SPIKE_RESET_RATIO = 2.0
    }
}
