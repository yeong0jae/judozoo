package judozoo.insight.infrastructure

import jakarta.persistence.Converter
import judozoo.insight.domain.Failure
import judozoo.library.ValueEnumConverter

@Converter(autoApply = true)
class FailureConverter : ValueEnumConverter<Failure>(Failure.entries, Failure::value)
