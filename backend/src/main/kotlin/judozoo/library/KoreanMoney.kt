package judozoo.library

import java.util.Locale
import kotlin.math.absoluteValue

/** 한국 금액 표기 — 필터 설명·실적값을 사람이 읽는 문자열로 만든다. 프론트가 그대로 뿌린다. Python `library/money.py`와 글자까지 같다. */
object KoreanMoney {
    private const val JO = 1_000_000_000_000L
    private const val EOK = 100_000_000L
    private const val MAN = 10_000L

    private fun comma(n: Long) = String.format(Locale.ROOT, "%,d", n)

    /** 300_000_000_000 → "3,000억원" / 1_073_000_000_000_000 → "1,073조원". */
    fun format(amount: Long): String {
        val sign = if (amount < 0) "-" else ""
        val a = amount.absoluteValue
        return when {
            a >= JO -> {
                val eok = a % JO / EOK
                if (eok > 0) "$sign${comma(a / JO)}조 ${comma(eok)}억원" else "$sign${comma(a / JO)}조원"
            }
            a >= EOK -> "$sign${comma(a / EOK)}억원"
            else -> "$sign${comma(a)}원"
        }
    }

    /** 1_234_567_890 → "12억 3,456만원". 억 미만 잔돈까지 보여야 하는 수급값에 쓴다. */
    fun formatWithMan(amount: Long): String {
        val sign = if (amount < 0) "-" else ""
        val a = amount.absoluteValue
        val man = a % EOK / MAN
        return when {
            a >= JO -> buildString {
                append("$sign${comma(a / JO)}조")
                val eok = a % JO / EOK
                if (eok > 0) append(" ${comma(eok)}억")
                if (man > 0) append(" ${comma(man)}만")
                append("원")
            }
            a >= EOK -> "$sign${comma(a / EOK)}억" + (if (man > 0) " ${comma(man)}만" else "") + "원"
            a >= MAN -> "$sign${comma(a / MAN)}만원"
            else -> "$sign${comma(a)}원"
        }
    }

    fun formatPrice(price: Long): String = "${comma(price)}원"
}
