package at.backend.library.format

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

object FormatUtils {

    private val numberFormat = NumberFormat.getNumberInstance(Locale.KOREA)

    /** 300_000_000_000 → "3,000억원" / 1_073_000_000_000_000 → "1,073조원" */
    fun formatKoreanMoney(amount: Long): String {
        val absAmount = abs(amount)
        val sign = if (amount < 0) "-" else ""
        return when {
            absAmount >= 1_000_000_000_000 -> {
                val jo = absAmount / 1_000_000_000_000
                val eok = (absAmount % 1_000_000_000_000) / 100_000_000
                if (eok > 0) "$sign${numberFormat.format(jo)}조 ${numberFormat.format(eok)}억원"
                else "$sign${numberFormat.format(jo)}조원"
            }
            absAmount >= 100_000_000 -> {
                val eok = absAmount / 100_000_000
                "$sign${numberFormat.format(eok)}억원"
            }
            else -> "$sign${numberFormat.format(absAmount)}원"
        }
    }

    /** 1_234_567_890 → "12억 3,456만원" */
    fun formatKoreanMoneyWithMan(amount: Long): String {
        val absAmount = abs(amount)
        val sign = if (amount < 0) "-" else ""
        return when {
            absAmount >= 1_000_000_000_000 -> {
                val jo = absAmount / 1_000_000_000_000
                val eok = (absAmount % 1_000_000_000_000) / 100_000_000
                val man = (absAmount % 100_000_000) / 10_000
                buildString {
                    append(sign)
                    append("${numberFormat.format(jo)}조")
                    if (eok > 0) append(" ${numberFormat.format(eok)}억")
                    if (man > 0) append(" ${numberFormat.format(man)}만")
                    append("원")
                }
            }
            absAmount >= 100_000_000 -> {
                val eok = absAmount / 100_000_000
                val man = (absAmount % 100_000_000) / 10_000
                buildString {
                    append(sign)
                    append("${numberFormat.format(eok)}억")
                    if (man > 0) append(" ${numberFormat.format(man)}만")
                    append("원")
                }
            }
            absAmount >= 10_000 -> {
                val man = absAmount / 10_000
                "$sign${numberFormat.format(man)}만원"
            }
            else -> "$sign${numberFormat.format(absAmount)}원"
        }
    }

    fun formatPrice(price: Long): String = "${numberFormat.format(price)}원"
}
