package at.backend.platform.kis.client.request

import com.fasterxml.jackson.annotation.JsonProperty

data class KisTokenRequest(
    @JsonProperty("appkey") val appKey: String,
    @JsonProperty("appsecret") val appSecret: String,
    @JsonProperty("grant_type") val grantType: String = "client_credentials",
)
