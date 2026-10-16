package judozoo.insight.domain

import judozoo.market.domain.Region
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * 한 시장의 생성 규칙. 모든 시각은 그 시장의 **현지 벽시계**다 — 국내 KST, 해외 뉴욕.
 * 해외 시각표를 뉴욕 기준으로 두면 서머타임이 바뀌어도 미국 장의 같은 순간에 돈다(026 §설계 결정).
 */
enum class Schedule(
    /** 이 시각부터 오늘을 보여준다(프리마켓 시작) — 그 전에는 직전 거래일 */
    private val reset: LocalTime,
    /** 새로 들어온 종목을 바로 만드는 구간(양 끝 포함) */
    private val windowStart: LocalTime,
    private val windowEnd: LocalTime,
    /** 그 시각의 주도주를 다시 만드는 시각들 */
    private val slots: List<LocalTime>,
    /** 정규장 마감 — 기사 범위의 시작("직전 거래일 장 마감 이후") */
    private val close: LocalTime,
) {
    KR(
        LocalTime.of(8, 0), LocalTime.of(8, 30), LocalTime.of(20, 0),
        listOf("08:30", "09:30", "11:00", "14:00", "16:00", "18:00", "20:00").map(LocalTime::parse), LocalTime.of(15, 30),
    ),

    /** KST(서머타임 중) 21:00 · 22:00 · 23:00 · 00:00 · 02:00 · 05:30 · 07:00 */
    US(
        LocalTime.of(4, 0), LocalTime.of(8, 0), LocalTime.of(18, 0),
        listOf("08:00", "09:00", "10:00", "11:00", "13:00", "16:30", "18:00").map(LocalTime::parse), LocalTime.of(16, 0),
    ),
    ;

    fun inWindow(at: LocalTime): Boolean = at.truncatedTo(ChronoUnit.MINUTES).let { !it.isBefore(windowStart) && !it.isAfter(windowEnd) }

    /**
     * 정해진 시각부터 5분 동안. 한 번 실행이 1분을 넘기면 다음 실행이 밀려 정각을 놓친다 —
     * 폭을 두고, 겹쳐 만드는 건 30분 규칙([RECENT])이 막는다.
     */
    fun isSlot(at: LocalTime): Boolean = slots.any { slot ->
        val elapsed = Duration.between(slot, at.truncatedTo(ChronoUnit.MINUTES))
        !elapsed.isNegative && elapsed < SLOT_GRACE
    }

    /** 기사 범위의 시작 — 직전 거래일 정규장 마감. 그보다 오래된 기사는 오늘 사유로 보기 어렵다. */
    fun articlesSince(previousOpenDay: LocalDate): LocalDateTime = previousOpenDay.atTime(close)

    /** 지금 보여줄 거래일. 초기화 작업 없이 조회 조건만으로 "프리마켓 시작에 초기화"가 된다. */
    fun sessionDay(localNow: LocalDateTime, todayOpen: Boolean, previousOpenDay: LocalDate): LocalDate =
        if (!todayOpen || localNow.toLocalTime().isBefore(reset)) previousOpenDay else localNow.toLocalDate()

    /**
     * 지금 만들 종목과 그 이유. 대상은 **지금 주도주**와 **등락률 기준을 넘은 후보**다.
     *
     * 후보는 새로 들어올 때·실패 재시도·**아직 설명 없음**일 때만 다시 만든다 — 매번 만들면 검색이 무료 한도를 넘는다(026 §설계 결정).
     */
    fun pick(leaders: List<String>, history: Map<String, Attempts>, now: LocalDateTime, candidates: List<String> = emptyList()): List<Pair<String, Trigger>> {
        if (!inWindow(now.toLocalTime())) return emptyList()
        val slot = isSlot(now.toLocalTime())
        val lead = leaders.toSet()
        return (leaders + candidates.filterNot { it in lead }).mapNotNull { code ->
            val done = history[code] ?: Attempts()
            when {
                done.isEmpty() -> code to Trigger.ENTRY
                done.needsRetry(now) -> code to Trigger.RETRY
                slot && !done.madeWithin(now, RECENT) && (code in lead || !done.explained) -> code to Trigger.SCHEDULED
                else -> null
            }
        }
    }

    companion object {
        val SLOT_GRACE: Duration = Duration.ofMinutes(5)

        /** 정해진 시각에 이만큼 안에 만든 종목은 건너뛴다 — 09:28 진입 → 09:29 생성 → 09:30 또 생성을 막는다 */
        val RECENT: Duration = Duration.ofMinutes(30)

        fun of(region: Region): Schedule = valueOf(region.name)
    }
}
