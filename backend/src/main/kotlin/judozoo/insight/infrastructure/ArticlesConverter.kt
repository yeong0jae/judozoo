package judozoo.insight.infrastructure

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import judozoo.insight.domain.Article
import judozoo.insight.domain.Articles
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue

@Converter(autoApply = true)
class ArticlesConverter : AttributeConverter<Articles, String> {
    override fun convertToDatabaseColumn(attribute: Articles?): String? = attribute?.let { JSON.writeValueAsString(it.items) }

    override fun convertToEntityAttribute(dbData: String?): Articles? = dbData?.let { Articles(JSON.readValue<List<Article>>(it)) }

    companion object {
        private val JSON = JsonMapper.builder().build()
    }
}
