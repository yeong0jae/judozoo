package judozoo.closingbet.domain

import judozoo.stock.domain.Market

class MarketFlows(private val markets: Map<Market, MarketFlow>, private val night: NightFutures?) {
    data class NightFutures(val price: Double, val rate: Double)

    fun late(): Map<Market, Long> = markets.mapValues { it.value.late }

    fun published(): Map<String, Any?> = markets.entries.associateTo(linkedMapOf<String, Any?>()) { (market, flow) ->
        market.name to mapOf("foreign" to flow.foreign.values(), "institution" to flow.institution.values())
    }.apply { put("night", night?.let { mapOf("price" to it.price, "rate" to it.rate) }) }
}
