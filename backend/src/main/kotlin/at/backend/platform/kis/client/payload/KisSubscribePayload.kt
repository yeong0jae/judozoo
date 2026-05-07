package at.backend.platform.kis.client.payload

import com.fasterxml.jackson.annotation.JsonProperty

// KIS WebSocket SUBSCRIBE / UNSUBSCRIBE envelope.
// tr_type: "1"=구독, "2"=구독해제. tr_id/tr_key 쌍이 채널 식별자.
data class KisSubscribePayload(
    val header: Header,
    val body: Body,
) {
    data class Header(
        @JsonProperty("approval_key") val approvalKey: String,
        val custtype: String,
        @JsonProperty("tr_type") val trType: String,
        @JsonProperty("content-type") val contentType: String = "utf-8",
    )

    data class Body(val input: Input) {
        data class Input(
            @JsonProperty("tr_id") val trId: String,
            @JsonProperty("tr_key") val trKey: String,
        )
    }
}
