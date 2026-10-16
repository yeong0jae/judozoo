package judozoo.platform.kiwoom

import tools.jackson.databind.JsonNode

class KiwoomResponse(
    val body: JsonNode,
    private val contYn: String?,
    val nextKey: String?,
) {
    val hasNext: Boolean get() = contYn == "Y" && !nextKey.isNullOrEmpty()
}
