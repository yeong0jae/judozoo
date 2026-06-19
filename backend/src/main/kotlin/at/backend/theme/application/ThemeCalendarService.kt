package at.backend.theme.application

import at.backend.library.time.TimeProvider
import at.backend.platform.kiwoom.client.KiwoomThemeClient
import at.backend.theme.domain.ThemeDailyRecord
import at.backend.theme.infrastructure.repository.ThemeDailyRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Service
class ThemeCalendarService(
    private val themeClient: KiwoomThemeClient,
    private val repository: ThemeDailyRepository,
    private val timeProvider: TimeProvider,
) {
    private val log = KotlinLogging.logger {}

    /** 오늘 당일 등락률 상위 테마를 적재. 같은 날 재실행 시 교체. 반환값=저장 건수. */
    @Transactional
    fun capture(): Int {
        val today = timeProvider.today()
        val top = themeClient.fetchTopThemes(CAPTURE_LIMIT)
        if (top.isEmpty()) {
            log.warn { "테마 캡처 — 상위 테마 응답 없음, 저장 생략 (date=$today)" }
            return 0
        }
        repository.deleteByDate(today)
        repository.flush() // 유니크(date, theme_grp_cd) 충돌 방지: 재적재 전 삭제 반영
        val saved = repository.saveAll(
            top.mapIndexed { i, t ->
                ThemeDailyRecord(
                    date = today,
                    rank = i + 1,
                    themeGrpCd = t.grpCd,
                    themeName = t.name,
                    fluRt = t.fluRt,
                )
            },
        )
        log.info { "테마 캡처 완료 — ${saved.size}건 (date=$today)" }
        return saved.size
    }

    @Transactional(readOnly = true)
    fun getCalendar(from: LocalDate, to: LocalDate): List<ThemeDailyRecord> =
        repository.findByDateBetweenOrderByDateAscRankAsc(from, to)

    companion object {
        private const val CAPTURE_LIMIT = 10
    }
}
