package at.backend.platform.kis.client.response

import com.fasterxml.jackson.annotation.JsonProperty

data class KisCurrentPriceResponse(
    val output: Output,
) {
    data class Output(
        @JsonProperty("stck_prpr") val stckPrpr: String,
    )
}
