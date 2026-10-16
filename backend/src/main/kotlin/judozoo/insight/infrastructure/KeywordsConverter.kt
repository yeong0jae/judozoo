package judozoo.insight.infrastructure

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import judozoo.insight.domain.Keywords
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue

@Converter(autoApply = true)
class KeywordsConverter : AttributeConverter<Keywords, String> {
    override fun convertToDatabaseColumn(attribute: Keywords?): String? = attribute?.let { JSON.writeValueAsString(it.values) }

    override fun convertToEntityAttribute(dbData: String?): Keywords? = dbData?.let { Keywords(JSON.readValue<List<String>>(it)) }

    companion object {
        private val JSON = JsonMapper.builder().build()
    }
}
