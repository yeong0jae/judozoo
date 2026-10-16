package judozoo.platform.kis

import tools.jackson.databind.JsonNode

class KisResponse(val status: Int, val body: JsonNode?) {
    val msgCd: String? get() = body?.get("msg_cd")?.asString()
    val msg1: String? get() = body?.get("msg1")?.asString()

    val ok: Boolean get() = status == 200 && body?.get("rt_cd")?.asString() == "0"

    val rateLimited: Boolean get() = msgCd == RATE_LIMITED

    companion object {
        const val RATE_LIMITED = "EGW00201"
    }
}
