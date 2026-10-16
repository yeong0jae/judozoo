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

    private val circuit = Circuit()

    internal var maxConcurrency = MAX_CONCURRENCY

    init {
        Gauge.builder("llm_circuit_state") { synchronized(circuit) { circuit.state(LocalDateTime.now(KST)).gauge.toDouble() } }
            .description("Vertex 서킷 — 0 닫힘 · 1 시험 · 2 열림").register(registry)
    }

    private data class Leader(val code: String, val name: String, val searchName: String, val rate: Double, val tradingValue: String)

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

        val room = maxOf(properties.dailyLimit - reasons.countByGeneratedAtGreaterThanEqual(nowKst.toLocalDate().atStartOfDay()).toInt(), 0)
        if (picks.size > room) {
            log.warn("왜 오르나 — 하루 상한에 닿아 오늘은 멈춘다 ({}개 남김)", picks.size - room)
            picks = picks.take(room)
        }
        if (state == Circuit.State.HALF_OPEN) picks = picks.take(1)
        if (picks.isEmpty()) return 0

        val started = elapsed()
        val pool = Executors.newFixedThreadPool(minOf(maxConcurrency, picks.size))
        try {
            val done = ExecutorCompletionService<StockReason?>(pool)
            picks.forEach { (code, trigger) ->
                done.submit {
                    if (synchronized(circuit) { circuit.state(nowKst) } == Circuit.State.OPEN) return@submit null
                    val row = make(region, schedule, byCode.getValue(code), trigger, now.toLocalDate(), nowKst)
                        .let { it.finishedAt(nowKst.plus(elapsed() - started)) }
                    record(row.failure, row.generatedAt)
                    row
                }
            }
            return (picks.indices).count { done.take().get()?.also { reasons.save(it) } != null }
        } finally {
            pool.shutdown()
        }
    }

    fun reasons(region: Region, nowKst: LocalDateTime): List<StockReason> =
        reasons.findByRegionAndTradingDay(region, sessionDay(region, nowKst)).groupBy { it.code }.values.mapNotNull { Shown.of(it) }

    internal fun sessionDay(region: Region, nowKst: LocalDateTime): LocalDate {
        val now = local(region, nowKst)
        return Schedule.of(region).sessionDay(now, !calendar.isHoliday(region), previousOpenDay(region, now.toLocalDate()))
    }

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

    private fun targets(region: Region): Pair<List<Leader>, List<Leader>> {
        val (leaders, pool) = if (region == Region.KR) {
            domestic.findLeaders(LEADERS_COUNT).map(::domesticLeader) to domestic.findCandidates(CANDIDATE_MIN_CHANGE_RATE).map(::domesticLeader)
        } else {
            overseas.leaders(LEADERS_COUNT).map(::overseasLeader) to overseas.candidates(CANDIDATE_MIN_CHANGE_RATE).map(::overseasLeader)
        }
        val lead = leaders.map { it.code }.toSet()
        return leaders to pool.filterNot { it.code in lead }
    }

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
        const val LEADERS_COUNT = 5

        const val CANDIDATE_MIN_CHANGE_RATE = 5.0

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
