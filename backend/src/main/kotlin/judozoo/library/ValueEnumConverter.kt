package judozoo.library

import jakarta.persistence.AttributeConverter

abstract class ValueEnumConverter<E : Enum<E>>(private val entries: List<E>, private val valueOf: (E) -> String) : AttributeConverter<E, String> {
    override fun convertToDatabaseColumn(attribute: E?): String? = attribute?.let(valueOf)

    override fun convertToEntityAttribute(dbData: String?): E? = dbData?.let { value -> entries.first { valueOf(it) == value } }
}
