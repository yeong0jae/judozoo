package judozoo.platform.kiwoom

import judozoo.library.KST
import judozoo.library.TtlCache
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Component
class KiwoomProgram(private val client: KiwoomClient, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val cache = TtlCache<Pair<String, LocalDate>, ProgramTradingData?>(
        "programTrading", Duration.ofSeconds(60), maxSize = 100, skipIf = { it == null },
    )

    fun fetchProgramTrading(stockCode: String): ProgramTradingData? {
        val today = LocalDate.now(clock.withZone(KST))
        return cache.get(stockCode to today) { load(stockCode, today) }
    }

    fun fetchProgramNetBuy(stockCode: String): Long = fetchProgramTrading(stockCode)?.programNetBuyAmount ?: 0

    private fun load(stockCode: String, today: LocalDate): ProgramTradingData? = try {
        log.info("키움 종목 프로그램매매 조회 stk_cd={}", stockCode)
        val body = client.query(
            MRKCOND_URL, "ka90013",
            mapOf("amt_qty_tp" to "1", "stk_cd" to stockCode, "date" to today.format(DateTimeFormatter.BASIC_ISO_DATE)),
        ).body
        body["stk_daly_prm_trde_trnsn"]?.firstOrNull()?.let { item ->
            ProgramTradingData(
                stockCode = stockCode,
                date = item["dt"]?.asString().orEmpty(),
                programSellAmount = parseAmount(item["prm_sell_amt"]?.asString()),
                programBuyAmount = parseAmount(item["prm_buy_amt"]?.asString()),
                programNetBuyAmount = parseAmount(item["prm_netprps_amt"]?.asString()),
            )
        }
    } catch (e: Exception) {
        log.error("키움 종목 프로그램매매 조회 실패 stk_cd={}", stockCode, e)
        null
    }

    companion object {
        private const val MRKCOND_URL = "/api/dostk/mrkcond"

        fun parseAmount(value: String?): Long {
            val s = value?.trim().orEmpty()
            return when {
                s.isEmpty() -> 0
                s.startsWith("--") -> s.substring(2).toLongOrNull()?.unaryMinus()
                s.endsWith("-") -> s.dropLast(1).toLongOrNull()?.unaryMinus()
                else -> s.toLongOrNull()
            } ?: 0
        }
    }
}
