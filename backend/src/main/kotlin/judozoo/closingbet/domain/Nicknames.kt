package judozoo.closingbet.domain

import java.time.Duration
import java.time.LocalDateTime
import kotlin.random.Random

/** 닉네임을 받지 않는 이유 — 화면에 그대로 보여 줄 문장. */
class NicknameRejected(message: String) : RuntimeException(message)

/** 닉네임 규칙 — 2~10자 한글·영문·숫자, 바꾼 뒤 7일은 못 바꾼다, 처음엔 형용사 + 동물. */
object Nicknames {
    private val PATTERN = Regex("^[가-힣a-zA-Z0-9]{2,10}$")
    val RENAME_COOLDOWN: Duration = Duration.ofDays(7)

    private val ADJECTIVES = listOf("용감한", "느긋한", "조용한", "단단한", "날쌘", "반짝이는", "푸른", "졸린", "새벽", "번개")
    private val ANIMALS = listOf("수달", "판다", "고래", "거북", "치타", "올빼미", "토끼", "돌고래", "여우", "다람쥐")

    fun check(raw: String, banned: Collection<String>): String {
        val nick = raw.trim()
        if (!PATTERN.matches(nick)) throw NicknameRejected("2~10자, 한글·영문·숫자만 쓸 수 있어요")
        if (banned.any { it.isNotEmpty() && it.lowercase() in nick.lowercase() }) throw NicknameRejected("쓸 수 없는 단어가 들어 있어요")
        return nick
    }

    /** 다음에 바꿀 수 있는 시각. 처음 받은 랜덤 닉네임은 바로 바꿀 수 있다. */
    fun nextRenameAt(renamedAt: LocalDateTime?): LocalDateTime? = renamedAt?.plus(RENAME_COOLDOWN)

    fun checkRename(renamedAt: LocalDateTime?, now: LocalDateTime) {
        val at = nextRenameAt(renamedAt) ?: return
        if (now.isBefore(at)) throw NicknameRejected("${at.monthValue}/${at.dayOfMonth}부터 바꿀 수 있어요")
    }

    /** 형용사 + 동물. 다 찼으면 숫자를 붙인다. */
    fun random(taken: Set<String>, rng: Random): String {
        val combos = ADJECTIVES.flatMap { a -> ANIMALS.map { a + it } }.filter { it.length <= 10 }
        combos.filterNot { it in taken }.takeIf { it.isNotEmpty() }?.let { return it.random(rng) }
        while (true) {
            val nick = combos.random(rng).take(8) + rng.nextInt(10, 100)
            if (nick !in taken) return nick
        }
    }
}
