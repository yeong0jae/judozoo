package judozoo.closingbet.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface BetRoundRepository : JpaRepository<BetRound, LocalDate>
