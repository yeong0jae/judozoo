package judozoo.closingbet.domain

/** 하루 네 칸 — 복기 09:05~15:00 · 베팅 15:00~20:00 · 대기 20:00~08:00 · 결과 08:00~09:05. 화면에는 [value]로 나간다. */
enum class Moment(val value: String) {
    REVIEW("review"),
    BET("bet"),
    NIGHT("night"),
    RESULT("result"),
}
