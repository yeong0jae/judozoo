package judozoo.platform.kiwoom

/**
 * 키움 부호 변종을 모두 흡수한다 — `"+1.23"`, `"1.23-"`(후위 부호), `"-1.23"`.
 *
 * 후위 부호가 진짜로 온다. 그냥 `toDouble()`에 넣으면 예외나 0으로 떨어져 등락률이 조용히 사라진다.
 * 순매수처럼 **부호가 의미를 갖는** 값에 쓴다. 가격 앞 부호는 방향 표식이라 다른 파서를 쓴다.
 */
fun String?.toSignedDouble(): Double = normalizeSign(this)?.toDoubleOrNull() ?: 0.0

fun String?.toSignedLong(): Long = normalizeSign(this)?.toLongOrNull() ?: 0L

private fun normalizeSign(value: String?): String? {
    val s = value?.trim().orEmpty()
    return when {
        s.isEmpty() -> null
        s.startsWith("+") -> s.substring(1)
        s.endsWith("-") -> "-" + s.dropLast(1)
        else -> s
    }
}
