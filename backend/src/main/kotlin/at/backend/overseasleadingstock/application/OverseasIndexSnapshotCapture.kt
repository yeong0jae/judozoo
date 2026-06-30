package at.backend.overseasleadingstock.application

import at.backend.library.time.TimeProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 미국 정규장 마감 뒤 나스닥종합 지수를 스냅샷으로 적재. 한국시간 06:10 단일 cron —
 * 서머타임(마감 05:00)·표준시(06:00) 모두 마감 뒤라 DST 분기 없이 종가를 받는다.
 * 영업일은 응답이 알려주는 실제 영업일을 쓰므로 미국 휴장일엔 중복 없이 멱등. 테스트 제외.
 */
@Component
@Profile("!test")
class OverseasIndexSnapshotCapture(
    private val snapshotService: OverseasIndexSnapshotService,
    private val timeProvider: TimeProvider,
) {
    private val log = KotlinLogging.logger {}

    @Scheduled(cron = "0 10 6 * * TUE-SAT", zone = "Asia/Seoul")
    fun onClose() {
        runCatching {
            val saved = snapshotService.capture(timeProvider.now())
            if (saved > 0) log.info { "해외지수 마감 스냅샷 ${saved}건 적재" }
        }.onFailure { log.warn(it) { "해외지수 마감 스냅샷 적재 실패" } }
    }
}
