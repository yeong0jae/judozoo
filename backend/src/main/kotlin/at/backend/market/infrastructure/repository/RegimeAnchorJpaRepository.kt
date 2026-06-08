package at.backend.market.infrastructure.repository

import at.backend.market.domain.regime.RegimeAnchorConstituent
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface RegimeAnchorJpaRepository : JpaRepository<RegimeAnchorConstituent, Long> {
    /** 당일 앵커 구성원 — 재시작 후 바스켓 복원용. */
    fun findByDate(date: LocalDate): List<RegimeAnchorConstituent>

    /** 당일 앵커가 이미 고정됐는지 — 중복 캡처 방지. */
    fun existsByDate(date: LocalDate): Boolean
}
