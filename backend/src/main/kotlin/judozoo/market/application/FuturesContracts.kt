package judozoo.market.application

import judozoo.library.KST
import judozoo.library.TtlCache
import judozoo.platform.kis.KisFutures
import judozoo.platform.kis.KisFutures.NearMonth
import judozoo.stock.domain.Market
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@Component
class FuturesContracts(private val futures: KisFutures, private val clock: Clock) {
    private data class Key(val market: Market, val day: LocalDate, val session: String)

    private val nearMonths = TtlCache<Key, NearMonth?>("futuresNearMonth", Duration.ofHours(24), maxSize = 4, skipIf = { it == null })

    fun nearMonth(market: Market): NearMonth? = nearMonths.get(key(market)) { futures.fetchNearMonth(market) }

    fun changed(market: Market, iscd: String): NearMonth? =
        nearMonths.refresh(key(market)) { futures.fetchNearMonth(market) }?.takeIf { it.iscd != iscd }

    private fun key(market: Market): Key {
        val at = LocalDateTime.now(clock.withZone(KST))
        if (at.toLocalTime().isBefore(LocalTime.of(6, 0))) return Key(market, at.toLocalDate().minusDays(1), "night")
        return Key(market, at.toLocalDate(), if (!at.toLocalTime().isBefore(LocalTime.of(18, 0))) "night" else "day")
    }
}
