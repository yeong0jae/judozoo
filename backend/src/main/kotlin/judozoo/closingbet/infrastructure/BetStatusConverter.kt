package judozoo.closingbet.infrastructure

import jakarta.persistence.Converter
import judozoo.closingbet.domain.BetStatus
import judozoo.library.ValueEnumConverter

@Converter(autoApply = true)
class BetStatusConverter : ValueEnumConverter<BetStatus>(BetStatus.entries, BetStatus::value)
