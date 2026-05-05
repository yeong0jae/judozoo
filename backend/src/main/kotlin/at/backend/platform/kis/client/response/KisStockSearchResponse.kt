package at.backend.platform.kis.client.response

import com.fasterxml.jackson.annotation.JsonProperty

data class KisStockSearchResponse(
    val output: Output,
) {
    data class Output(
        val pdno: String,
        @JsonProperty("prdt_abrv_name") val prdtAbrvName: String,
    )
}
