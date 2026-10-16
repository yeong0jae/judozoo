package judozoo.closingbet.domain

class BetRejected(message: String) : RuntimeException(message)

object BetRules {
    const val MIN_AMOUNT_MAN = 100

    const val MAX_AMOUNT_MAN = 10_000
    const val AMOUNT_STEP_MAN = 100

    fun checkAmount(amountMan: Int) {
        if (amountMan !in MIN_AMOUNT_MAN..MAX_AMOUNT_MAN || amountMan % AMOUNT_STEP_MAN != 0) {
            throw BetRejected("100만원부터 1억까지 100만원 단위로 걸 수 있어요")
        }
    }

    fun sharesOrNull(amountMan: Int, price: Long): Int? {
        val shares = if (price > 0) amountMan * 10_000L / price else 0
        return shares.takeIf { it >= 1 }?.toInt()
    }

    fun sharesFor(amountMan: Int, price: Long): Int = sharesOrNull(amountMan, price) ?: throw BetRejected("이 금액으로는 한 주도 살 수 없어요")

    fun check(phase: Phase, amountMan: Int, stockCode: String, pool: Collection<String>, price: Long): Int {
        if (!phase.acceptsBets) throw BetRejected("베팅은 15:00부터 20:00까지예요")
        checkAmount(amountMan)
        if (stockCode !in pool) throw BetRejected("지금 주도주·후보 목록에 있는 종목만 고를 수 있어요")
        return sharesFor(amountMan, price)
    }
}
