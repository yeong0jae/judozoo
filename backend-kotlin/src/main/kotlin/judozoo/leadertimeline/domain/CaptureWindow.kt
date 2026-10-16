package judozoo.leadertimeline.domain

import judozoo.market.domain.Region
import java.time.LocalTime

/**
 * 어느 분을 찍는가. 시각은 **각 시장의 현지 시각**(국내 KST, 해외 뉴욕)으로 받는다.
 *
 * 찍는 시간은 홈 풀이 도는 세션과 같다. **끝 시각(마감 분)도 찍는다** — 국내·해외 모두 20:00이 마지막 분이고,
 * 애프터마켓 마감 순간의 값이라 화면 끝 눈금(국내 20시·해외 09시)과 맞는다.
 *
 * 국내는 쉬는 구간이 있다 — 프리마켓이 끝나고 정규장이 열리기 전, 정규장이 닫히고 애프터마켓이 열리기 전.
 * 단일가·장 전환 구간이라 순위가 멈춰 있거나 뜻이 흐리다. 해외는 세 세션이 쉬지 않고 이어진다.
 */
enum class CaptureWindow(private val start: LocalTime, private val end: LocalTime, private val gaps: List<ClosedOpen>) {
    KR(LocalTime.of(8, 1), LocalTime.of(20, 0), listOf(ClosedOpen(LocalTime.of(8, 50), LocalTime.of(9, 0)), ClosedOpen(LocalTime.of(15, 30), LocalTime.of(15, 40)))),
    US(LocalTime.of(4, 0), LocalTime.of(20, 0), emptyList()),
    ;

    data class ClosedOpen(val from: LocalTime, val until: LocalTime) {
        operator fun contains(at: LocalTime) = !at.isBefore(from) && at.isBefore(until)
    }

    /** 현지 시각 [at]의 분을 찍는가 — 세션 안이고 쉬는 구간이 아니면. */
    fun captures(at: LocalTime): Boolean = !at.isBefore(start) && !at.isAfter(end) && gaps.none { at in it }

    companion object {
        fun of(region: Region): CaptureWindow = valueOf(region.name)
    }
}
