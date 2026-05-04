package at.backend.platform.kis.client.response

import com.fasterxml.jackson.annotation.JsonProperty

data class KisApprovalResponse(
    @JsonProperty("approval_key") val approvalKey: String,
)
