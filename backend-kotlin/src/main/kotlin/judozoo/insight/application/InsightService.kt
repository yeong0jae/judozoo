package judozoo.insight.application

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import judozoo.insight.domain.Circuit
import judozoo.insight.domain.Draft
import judozoo.insight.domain.Failure
import judozoo.insight.domain.Schedule
import judozoo.insight.domain.Shown
import judozoo.insight.domain.Sources
import judozoo.insight.domain.StockReason
import judozoo.insight.domain.StockReasonRepository
import judozoo.insight.domain.Trigger
import judozoo.insight.domain.Verdict
import judozoo.insight.domain.Attempts
import judozoo.leadercalendar.domain.ForeignSession
import judozoo.leadingstock.application.LeadingStockService
import judozoo.leadingstock.domain.LeadingStockSnapshot
import judozoo.library.KST
import judozoo.market.application.MarketCalendar
import judozoo.market.domain.Region
import judozoo.overseasleadingstock.application.OverseasLeadingStockService
import judozoo.overseasleadingstock.domain.OverseasStockRank
import judozoo.platform.vertex.NewsArticles
import judozoo.platform.vertex.VertexClient
import judozoo.platform.vertex.VertexException
import judozoo.platform.vertex.VertexProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors

/**
 * 주도주 "왜 오르나" — 매분 만들 종목을 골라 Gemini로 사유를 만들고, 화면에는 고른 한 행씩 내준다.
 *
 * 무엇이 주도주인지는 홈 카드가 정한다. 여기서는 그 5종목과, 등락률 5% 넘게 오른 나머지 후보에 사유를 붙인다(후보는 새로 들어올 때 한 번만).
 * Gemini는 두 번 부른다 — 검색 켠 문장 → 서버가 출처를 실제 기사로 → 검색 없는 JSON(번호만)(026 §생성 파이프라인).
 */
@Service
class InsightService(
    private val reasons: StockReasonRepository,
    private val domestic: LeadingStockService,
    private val overseas: OverseasLeadingStockService,
    private val calendar: MarketCalendar,
    private val vertex: VertexClient,
    private val articles: NewsArticles,
    private val properties: VertexProperties,
    private val registry: MeterRegistry,
    private val elapsed: () -> Duration = { Duration.ofNanos(System.nanoTime()) },
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 국내·해외가 같은 Vertex를 부르므로 서킷도 하나다. 프로세스가 새로 뜨면(재배포) 닫힌 채로 시작한다 */
    private val circuit = Circuit()

    /** 동시에 만드는 종목 수. 테스트가 줄여 차례를 정한다 */
    internal var maxConcurrency = MAX_CONCURRENCY

    init {
        Gauge.builder("llm_circuit_state") { synchronized(circuit) { circuit.state(LocalDateTime.now(KST)).gauge.toDouble() } }
            .description("Vertex 서킷 — 0 닫힘 · 1 시험 · 2 열림").register(registry)
    }

    private data class Leader(val code: String, val name: String, val searchName: String, val rate: Double, val tradingValue: String)

    /** 지금 만들 종목을 골라 만든다. 만든 행 수를 돌려준다. 휴장일은 부르는 쪽(스케줄러)이 거른다. */
    fun run(region: Region, nowKst: LocalDateTime): Int {
        val schedule = Schedule.of(region)
        val now = local(region, nowKst)
        if (!schedule.inWindow(now.toLocalTime())) return 0
        val state = synchronized(circuit) { circuit.state(nowKst) }
        if (state == Circuit.State.OPEN) return 0

        val (leaders, candidates) = targets(region)
        val byCode = (candidates + leaders).associateBy { it.code }
        val history = reasons.findByRegionAndTradingDay(region, now.toLocalDate()).groupBy { it.code }
            .mapValues { (_, rows) -> Attempts(rows.map { it.attempt(local(region, it.generatedAt)) }) }
        var picks = schedule.pick(leaders.map { it.code }, history, now, candidates.map { it.code })

        // 동시에 돌면 저장 전이라 종목마다 세어도 같은 수가 나온다 — 시작 전에 한 번 세어 남은 만큼만 남긴다
        val room = maxOf(properties.dailyLimit - reasons.countByGeneratedAtGreaterThanEqual(nowKst.toLocalDate().atStartOfDay()).toInt(), 0)
        if (picks.size > room) {
            log.warn("왜 오르나 — 하루 상한에 닿아 오늘은 멈춘다 ({}개 남김)", picks.size - room)
            picks = picks.take(room)
        }
        // 한 종목으로 시험한다. 나머지는 행 없이 건너뛰어, 서킷이 닫힌 뒤 다시 골라진다
        if (state == Circuit.State.HALF_OPEN) picks = picks.take(1)
        if (picks.isEmpty()) return 0

        val started = elapsed()
        val pool = Executors.newFixedThreadPool(minOf(maxConcurrency, picks.size))
        try {
            val done = ExecutorCompletionService<StockReason?>(pool)
            picks.forEach { (code, trigger) ->
                done.submit {
                    // 출발 직전에 서킷을 본다. 끝나자마자 기록한다
                    if (synchronized(circuit) { circuit.state(nowKst) } == Circuit.State.OPEN) return@submit null
                    // 종목당 10~15초라 시작 시각에 경과를 더한다. 벽시계를 다시 읽지 않아 30분 규칙·서킷이 같은 시계를 쓴다
                    val row = make(region, schedule, byCode.getValue(code), trigger, now.toLocalDate(), nowKst)
                        .let { it.finishedAt(nowKst.plus(elapsed() - started)) }
                    record(row.failure, row.generatedAt)
                    row
                }
            }
            // 끝난 종목부터 하나씩 남긴다 — 느리거나 실패한 종목이 다른 종목을 붙잡지 않는다
            return (picks.indices).count { done.take().get()?.also { reasons.save(it) } != null }
        } finally {
            pool.shutdown()
        }
    }

    /** 지금 세션의 종목별 사유 — 종목마다 [Shown]이 고른 한 행. */
    fun reasons(region: Region, nowKst: LocalDateTime): List<StockReason> =
        reasons.findByRegionAndTradingDay(region, sessionDay(region, nowKst)).groupBy { it.code }.values.mapNotNull { Shown.of(it) }

    /** 지금 화면이 보여줄 거래일. 프리마켓 시작(국내 08:00·뉴욕 04:00) 전이나 휴장일이면 직전 거래일. */
    internal fun sessionDay(region: Region, nowKst: LocalDateTime): LocalDate {
        val now = local(region, nowKst)
        return Schedule.of(region).sessionDay(now, !calendar.isHoliday(region), previousOpenDay(region, now.toLocalDate()))
    }

    /** 한 종목. Vertex가 실패해도 행은 남긴다 — 실패 종류를 보고 [Schedule.pick]이 다시 만들지 정한다. */
    private fun make(region: Region, schedule: Schedule, leader: Leader, trigger: Trigger, day: LocalDate, nowKst: LocalDateTime): StockReason {
        val since = schedule.articlesSince(previousOpenDay(region, day))
        val verdict = try {
            val grounded = vertex.ground(explainPrompt(region, leader, day, since))
            val sources = Sources.of(grounded.sources.mapNotNull { s -> articles.resolve(s.uri)?.let { Sources.Raw(it.site ?: s.domain, it.title, it.url) } })
            val (data, _) = vertex.structure(structurePrompt(leader, day, grounded.text, sources), SCHEMA)
            Draft.of(data).judge(sources, leader.name)
        } catch (e: VertexException) {
            Verdict(published = false, error = e.message, failure = Failure.of(e.kind.label))
        }
        if (!verdict.published) log.info("왜 오르나 거절 {} {} — {}", region, leader.code, verdict.error)
        return StockReason.made(region, day, leader.code, leader.name, nowKst, trigger, verdict, properties.model)
    }

    private fun record(failure: Failure?, at: LocalDateTime) {
        if (failure != null) registry.counter("llm_failures", "model", properties.model, "kind", failure.value).increment()
        if (failure == Failure.CLIENT_ERROR) log.error("왜 오르나 — Vertex 클라이언트 오류. 설정을 고쳐 재배포할 때까지 Vertex를 부르지 않는다")
        val opened = synchronized(circuit) {
            val before = circuit.state(at)
            circuit.record(failure, at)
            before != Circuit.State.OPEN && circuit.state(at) == Circuit.State.OPEN
        }
        if (opened) log.warn("왜 오르나 — Vertex 서킷 열림 ({})", failure?.value.orEmpty())
    }

    /** (주도주, 등락률 기준을 넘은 나머지 후보). 후보 쪽은 주도주를 뺀다. */
    private fun targets(region: Region): Pair<List<Leader>, List<Leader>> {
        val (leaders, pool) = if (region == Region.KR) {
            domestic.findLeaders(LEADERS_COUNT).map(::domesticLeader) to domestic.findCandidates(CANDIDATE_MIN_CHANGE_RATE).map(::domesticLeader)
        } else {
            overseas.leaders(LEADERS_COUNT).map(::overseasLeader) to overseas.candidates(CANDIDATE_MIN_CHANGE_RATE).map(::overseasLeader)
        }
        val lead = leaders.map { it.code }.toSet()
        return leaders to pool.filterNot { it.code in lead }
    }

    /** 국내는 개장일 목록으로(연휴를 건너뛴다). 해외는 목록이 없어 직전 평일 — 미국 공휴일 다음 날엔 하루 넓어진다. */
    private fun previousOpenDay(region: Region, day: LocalDate): LocalDate =
        (if (region == Region.KR) calendar.previousOpenDay(day) else null) ?: ForeignSession.pairedWith(day)

    private fun explainPrompt(region: Region, leader: Leader, day: LocalDate, since: LocalDateTime): String {
        val (market, zone) = if (region == Region.KR) "국내" to "KST" else "미국" to "뉴욕 시각"
        return "${leader.searchName}(${leader.code})이 $day $market 증시에서 ${signed(leader.rate)}% 올랐다" +
            "(거래대금 ${leader.tradingValue}). 오른 이유를 Google 검색으로 찾아 한국어 한 문단으로 설명해라.\n" +
            "- ${since.format(SINCE)} $zone 이후에 나온 기사만 근거로 삼는다.\n" +
            "- 기사로 설명되지 않으면 그렇다고 말한다. 추측으로 이유를 만들지 않는다.\n" +
            "- 직접 매수·매도를 권하거나 종목을 추천하지 않는다. 기사에 나온 증권사의 목표가·투자의견 변경 사실은 출처의 판단임을 밝혀 전달해도 된다."
    }

    private fun structurePrompt(leader: Leader, day: LocalDate, explanation: String, sources: Sources): String =
        "종목: ${leader.name}(${leader.code}), $day, ${signed(leader.rate)}%\n\n" +
            "검색으로 정리한 설명:\n$explanation\n\n기사 목록:\n${sources.listing()}\n\n" +
            "위 설명과 기사 목록만 보고 JSON으로 답해라.\n" +
            "- reason: 오른 이유 한 줄. 한국어 30자 안팎. '~다'·'~했다'로 끝내지 말고 명사형으로 끊는다. 마침표 없이. " +
            "예: '1,500억 달러 규모 자사주 매입 추가 승인에 매수세 유입', '황반변성 치료제 후보물질 임상 3상 성공 결과 발표'. " +
            "직접적인 투자 권유 금지. 증권사의 목표가·투자의견 변경 사실은 주체를 밝혀 전달해도 된다.\n" +
            "- keywords: 사유의 핵심 단어 최대 2개. 회사명·제품명·계약 상대 같은 고유명사와 사건을 우선한다. " +
            "종목 자신의 이름(${leader.name})과 '상승'·'호재'·'기대'·'강세' 같은 일반어는 쓰지 않는다. 각 8자 안팎.\n" +
            "- evidence: 사유를 직접 뒷받침하는 기사 번호 최대 2개. " +
            "가격만 전하는 기사나 전망 기사는 근거가 아니다.\n" +
            "- related: 사유와 부딪히는 기사나 수급을 다룬 기사 번호 최대 2개.\n" +
            "- 목록에 없는 번호는 쓰지 않는다.\n" +
            "- 근거 기사가 없으면 explained=false, reason은 빈 문자열, keywords와 evidence는 빈 배열."

    companion object {
        /** 홈 주도주 카드의 줄 수와 같다 */
        const val LEADERS_COUNT = 5

        /** 주도주가 아니어도 이만큼(%) 넘게 오른 후보는 사유를 만든다 — 새로 들어올 때 한 번만(026) */
        const val CANDIDATE_MIN_CHANGE_RATE = 5.0

        /** 동시에 만드는 종목 수. 넘치면 빈 자리가 나는 대로 이어 만든다 */
        const val MAX_CONCURRENCY = 10

        private val SINCE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        val SCHEMA: Map<String, Any> = mapOf(
            "type" to "OBJECT",
            "properties" to mapOf(
                "explained" to mapOf("type" to "BOOLEAN"),
                "keywords" to mapOf("type" to "ARRAY", "items" to mapOf("type" to "STRING")),
                "reason" to mapOf("type" to "STRING"),
                "evidence" to mapOf("type" to "ARRAY", "items" to mapOf("type" to "INTEGER")),
                "related" to mapOf("type" to "ARRAY", "items" to mapOf("type" to "INTEGER")),
            ),
            "required" to listOf("explained", "keywords", "reason", "evidence", "related"),
        )

        /** KST 벽시계 → 그 시장의 현지 벽시계. */
        fun local(region: Region, atKst: LocalDateTime): LocalDateTime = atKst.atZone(KST).withZoneSameInstant(region.zone).toLocalDateTime()

        private fun signed(rate: Double) = String.format(Locale.ROOT, "%+.2f", rate)

        private fun domesticLeader(s: LeadingStockSnapshot) = Leader(
            s.stockCode.substringBefore("_"), s.stockName, s.stockName, s.priceChangeRate,
            String.format(Locale.ROOT, "%,.0f억 원", s.accumulatedTradingValue / 1e8),
        )

        private fun overseasLeader(s: OverseasStockRank) = Leader(
            s.symbol, s.name, s.ename.ifEmpty { s.name }, s.rate, String.format(Locale.ROOT, "$%,.2fB", s.tradingValue / 1e9),
        )
    }
}
