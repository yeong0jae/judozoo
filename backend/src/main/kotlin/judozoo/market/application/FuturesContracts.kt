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

/** 지수선물 근월물 코드. 시세·정규장/야간 차트가 같은 코드를 쓴다 — 전광판 조회를 한 번 나눠 쓴다. */
@Component
class FuturesContracts(private val futures: KisFutures, private val clock: Clock) {

    private data class Key(val market: Market, val day: LocalDate, val session: String)

    private val nearMonths = TtlCache<Key, NearMonth?>("futuresNearMonth", Duration.ofHours(24), maxSize = 4, skipIf = { it == null })

    /** 만기 교체는 시세 실패 시 [changed]로 즉시 다시 확인한다. */
    fun nearMonth(market: Market): NearMonth? = nearMonths.get(key(market)) { futures.fetchNearMonth(market) }

    /** 기존 종목 조회가 실패했을 때만 전광판을 다시 보고, 코드가 바뀌었으면 그 근월물. 그대로면 null. */
    fun changed(market: Market, iscd: String): NearMonth? =
        nearMonths.refresh(key(market)) { futures.fetchNearMonth(market) }?.takeIf { it.iscd != iscd }

    /** 정규장·야간장 경계에서는 만기 교체를 바로 확인하고, 자정에는 야간 코드를 유지한다. */
    private fun key(market: Market): Key {
        val at = LocalDateTime.now(clock.withZone(KST))
        if (at.toLocalTime().isBefore(LocalTime.of(6, 0))) return Key(market, at.toLocalDate().minusDays(1), "night")
        return Key(market, at.toLocalDate(), if (!at.toLocalTime().isBefore(LocalTime.of(18, 0))) "night" else "day")
    }
}
