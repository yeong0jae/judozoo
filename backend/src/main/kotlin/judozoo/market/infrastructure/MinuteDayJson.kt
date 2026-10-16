package judozoo.market.infrastructure

import judozoo.market.domain.MarketCandle
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

internal object MinuteDayJson {
    private val json = JsonMapper.builder().build()

    private val ISO_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    private val SECONDS = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun byTimestamp(candles: List<MarketCandle>): String = json.writeValueAsString(
        candles.sortedBy { it.at }.map {
            mapOf("timestamp" to it.at.format(ISO_SECONDS), "open" to it.open, "high" to it.high, "low" to it.low, "close" to it.close, "volume" to it.volume)
        },
    )

    fun byTimestamp(text: String): List<MarketCandle> = json.readTree(text).values().map {
        MarketCandle(LocalDateTime.parse(it["timestamp"].asString()), it.double("open"), it.double("high"), it.double("low"), it.double("close"), it.double("volume"))
    }

    fun byDateAndTime(candles: List<MarketCandle>): String = json.writeValueAsString(
        candles.sortedBy { it.at }.map {
            mapOf(
                "date" to it.at.toLocalDate().toString(), "time" to it.at.toLocalTime().format(SECONDS),
                "open" to it.open, "high" to it.high, "low" to it.low, "close" to it.close, "volume" to it.volume,
            )
        },
    )

    fun byDateAndTime(text: String): List<MarketCandle> = json.readTree(text).values().map {
        MarketCandle(
            LocalDate.parse(it["date"].asString()).atTime(LocalTime.parse(it["time"].asString())),
            it.double("open"), it.double("high"), it.double("low"), it.double("close"), it.double("volume"),
        )
    }

    private fun JsonNode.double(field: String) = get(field).asDouble()
}
