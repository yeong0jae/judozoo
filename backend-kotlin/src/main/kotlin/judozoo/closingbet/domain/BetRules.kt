package judozoo.closingbet.domain

/** 베팅을 받지 않는 이유. 메시지는 화면에 그대로 보여 줄 문장이다. */
class BetRejected(message: String) : RuntimeException(message)

/** 얼마를 어디에 걸 수 있나. 금액 단위는 만원. */
object BetRules {
    /** 100만원 */
    const val MIN_AMOUNT_MAN = 100

    /** 1억 */
    const val MAX_AMOUNT_MAN = 10_000
    const val AMOUNT_STEP_MAN = 100

    fun checkAmount(amountMan: Int) {
        if (amountMan !in MIN_AMOUNT_MAN..MAX_AMOUNT_MAN || amountMan % AMOUNT_STEP_MAN != 0) {
            throw BetRejected("100만원부터 1억까지 100만원 단위로 걸 수 있어요")
        }
    }

    /** ⌊금액 ÷ 가격⌋. 한 주도 못 사면 베팅할 수 없다. */
    fun sharesFor(amountMan: Int, price: Long): Int {
        val shares = if (price > 0) amountMan * 10_000L / price else 0
        if (shares < 1) throw BetRejected("이 금액으로는 한 주도 살 수 없어요")
        return shares.toInt()
    }

    /** 베팅·변경 검증. 통과하면 지금 가격 기준 예상 주식 수를 준다(확정은 20:00 종가). */
    fun check(phase: Phase, amountMan: Int, stockCode: String, pool: Collection<String>, price: Long): Int {
        if (!phase.acceptsBets) throw BetRejected("베팅은 15:00부터 20:00까지예요")
        checkAmount(amountMan)
        if (stockCode !in pool) throw BetRejected("지금 주도주·후보 목록에 있는 종목만 고를 수 있어요")
        return sharesFor(amountMan, price)
    }
}
