package judozoo.insight.infrastructure

import jakarta.persistence.Converter
import judozoo.insight.domain.Trigger
import judozoo.library.ValueEnumConverter

@Converter(autoApply = true)
class TriggerConverter : ValueEnumConverter<Trigger>(Trigger.entries, Trigger::value)
