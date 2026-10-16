package judozoo.closingbet.infrastructure

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import judozoo.closingbet.domain.Checks
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue

@Converter(autoApply = true)
class ChecksConverter : AttributeConverter<Checks, String> {
    override fun convertToDatabaseColumn(attribute: Checks?): String? = attribute?.let {
        JSON.writeValueAsString(
            mapOf(
                "near_high" to it.nearHigh, "foreign" to it.foreign, "institution" to it.institution,
                "market_late" to it.marketLate, "recent_high" to it.recentHigh, "after_hold" to it.afterHold,
            ),
        )
    }

    override fun convertToEntityAttribute(dbData: String?): Checks? = dbData?.let {
        val map = JSON.readValue<Map<String, Boolean>>(it)
        Checks(
            map["near_high"] ?: false, map["foreign"] ?: false, map["institution"] ?: false,
            map["market_late"] ?: false, map["recent_high"] ?: false, map["after_hold"] ?: false,
        )
    }

    companion object {
        private val JSON = JsonMapper.builder().build()
    }
}
