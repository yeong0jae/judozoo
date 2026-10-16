package judozoo.platform.kiwoom

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
