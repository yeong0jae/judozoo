package judozoo.platform.kiwoom

import judozoo.library.KST
import judozoo.library.TtlCache
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 키움 프로그램 매매 — 종목 일별(ka90013). 금액 단위는 백만원(amt_qty_tp=1). */
@Component
class KiwoomProgram(private val client: KiwoomClient, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 날짜를 키에 넣는다 — 자정을 넘기면 어제 값을 들고 있지 않게
    private val cache = TtlCache<Pair<String, LocalDate>, ProgramTradingData?>(
        "programTrading", Duration.ofSeconds(60), maxSize = 100, skipIf = { it == null },
    )

    /** 종목 일별 프로그램 매매 추이. 실패하거나 목록이 빠지면 null — 캐시하지 않는다. */
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
        // 응답에서 통째로 빠지는 경우가 있다 — 운영 로그에서 확인됐다
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

        /**
         * 키움이 음수를 내는 변종을 전부 흡수한다 — `"-123"`, `"123-"`, `"--123"`.
         * `"--123"`이 실제로 온다. 앞의 하나만 떼면 부호가 뒤집힌다.
         */
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
