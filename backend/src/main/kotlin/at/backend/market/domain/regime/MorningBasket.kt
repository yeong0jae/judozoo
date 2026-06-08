package at.backend.market.domain.regime

/**
 * 08:15 오전 NXT 바스켓의 구성원 한 종목.
 *
 * - [rateAt0815]: 전일 종가 대비 등락률(%) — 08:15 시점에 고정.
 * - [weight]: 거래대금 — 08:15 시점에 고정한 가중치.
 */
data class BasketConstituent(
    val stockCode: String,
    val rateAt0815: Double,
    val weight: Long,
)

/**
 * 08:15에 고정한 오전 NXT 바스켓(거래대금 상위 ex-ETF Top N).
 *
 * 두 갭을 거래대금 가중으로 산출한다. 외부 의존 없이 받은 값으로만 계산한다.
 * - Gap1: 전일 종가 → 08:15 NXT (고정값)
 * - Gap2: 08:15 NXT → 현재 본장 (현재 등락률을 받아 계산)
 */
class MorningBasket(private val constituents: List<BasketConstituent>) {

    private val totalWeight: Long = constituents.sumOf { it.weight }

    init {
        require(constituents.isNotEmpty()) { "바스켓은 비어 있을 수 없다" }
        require(totalWeight > 0) { "바스켓 가중치 합은 0보다 커야 한다" }
    }

    /** Gap1(%): 전일 종가 대비 08:15 NXT — 거래대금 가중 평균 등락률. */
    val gap1: Double = constituents.sumOf { it.rateAt0815 * it.weight } / totalWeight

    /**
     * Gap2: 08:15 대비 현재 본장.
     *
     * 종목별로 `(현재가 / 08:15가 − 1)`을 08:15 가중으로 평균한다. 가격 대신
     * "전일 종가 대비 등락률"로 계산한다: `(1+now)/(1+r0815) − 1 = 현재가/08:15가 − 1`.
     *
     * 현재 시세에 없는(거래대금 랭킹에서 빠진) 종목은 **제외**하고, 빠진 가중 비율로
     * 신뢰도(coverage)를 함께 낸다. 빈칸을 직전값으로 메우지 않는다 — 생존 편향 방지.
     */
    fun gap2(currentRates: Map<String, Double>): Gap2 {
        val present = constituents.filter { currentRates.containsKey(it.stockCode) }
        val presentWeight = present.sumOf { it.weight }
        if (presentWeight == 0L) return Gap2(value = 0.0, coverage = 0.0)

        val weightedSum = present.sumOf { c ->
            val now = currentRates.getValue(c.stockCode)
            val stockGap2 = (1 + now / 100) / (1 + c.rateAt0815 / 100) - 1
            stockGap2 * c.weight
        }
        return Gap2(
            value = weightedSum / presentWeight * 100,
            coverage = presentWeight.toDouble() / totalWeight,
        )
    }
}

/**
 * Gap2 결과. [coverage](살아있는 가중 비율)가 [RELIABLE_COVERAGE] 미만이면
 * 빠진 종목이 많아 편향 위험이 있으므로 신뢰할 수 없다([reliable] = false).
 */
data class Gap2(val value: Double, val coverage: Double) {
    val reliable: Boolean = coverage >= RELIABLE_COVERAGE

    companion object {
        const val RELIABLE_COVERAGE = 0.95
    }
}
