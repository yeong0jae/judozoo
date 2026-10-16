package judozoo.insight.application

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import judozoo.IntegrationTest
import judozoo.insight.domain.Article
import judozoo.insight.domain.Failure
import judozoo.insight.domain.StockReason
import judozoo.insight.domain.StockReasonRepository
import judozoo.insight.domain.Trigger
import judozoo.leadingstock.application.LeadingStockService
import judozoo.leadingstock.domain.LeadingStockSnapshot
import judozoo.market.application.MarketCalendar
import judozoo.market.domain.Region
import judozoo.overseasleadingstock.application.OverseasLeadingStockService
import judozoo.platform.vertex.NewsArticles
import judozoo.platform.vertex.VertexClient
import judozoo.platform.vertex.VertexClient.Grounded
import judozoo.platform.vertex.VertexClient.GroundedSource
import judozoo.platform.vertex.VertexClient.Usage
import judozoo.platform.vertex.VertexException
import judozoo.platform.vertex.VertexException.Kind
import judozoo.platform.vertex.VertexProperties
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

@IntegrationTest
class InsightServiceTest @Autowired constructor(private val repository: StockReasonRepository, private val jdbc: JdbcTemplate) {
    private val today = LocalDate.of(2026, 9, 28)
    private val json = JsonMapper.builder().build()

    private fun kst(h: Int, m: Int, day: LocalDate = today) = day.atTime(h, m)

    private fun stock(code: String, name: String) = LeadingStockSnapshot("${code}_AL", name, 575_000, 7.48, 1, 340_700_000_000)

    private var leaders = listOf(stock("011070", "LG이노텍"))
    private var candidates = emptyList<LeadingStockSnapshot>()
    private val domestic = mockk<LeadingStockService> {
        every { findLeaders(5) } answers { leaders }
        every { findCandidates(5.0) } answers { candidates }
    }
    private val calendar = mockk<MarketCalendar> {
        every { previousOpenDay(any()) } returns LocalDate.of(2026, 9, 25)
        every { isHoliday(any()) } returns false
    }
    private val answer = Grounded(
        "美 라이다 업체 Aeva 지분가치가 올랐다.",
        listOf(GroundedSource("fnnews.com", "https://redirect/A"), GroundedSource("jkn.co.kr", "https://redirect/B")),
        Usage(searchQueries = 3),
    )
    private var grounds: MutableList<() -> Grounded> = mutableListOf()
    private val prompts = mutableListOf<String>()
    private var structured = """{"explained":true,"keywords":["Aeva","LG이노텍"],"reason":"美 라이다 업체 Aeva 지분가치 상승","evidence":[1],"related":[2]}"""
    private val vertex = mockk<VertexClient> {
        every { ground(any()) } answers {
            synchronized(prompts) { prompts += firstArg<String>() }
            (synchronized(grounds) { grounds.removeFirstOrNull() } ?: { answer })()
        }
        every { structure(any(), any()) } answers { json.readTree(structured) to Usage() }
    }
    private val articles = mockk<NewsArticles> {
        every { resolve("https://redirect/A") } returns NewsArticles.Article("https://fnnews.com/1", "LG이노텍, 美라이다 기업 지분가치 상승 영향 - 파이낸셜뉴스", "파이낸셜뉴스")
        every { resolve("https://redirect/B") } returns NewsArticles.Article("https://jkn.co.kr/2", "LG이노텍, 호재성 뉴스 부재 속 급등", null)
    }
    private var dailyLimit = 150
    private var clock = Duration.ZERO

    private fun service() = InsightService(
        repository, domestic, mockk<OverseasLeadingStockService>(), calendar, vertex, articles, VertexProperties(dailyLimit = dailyLimit), SimpleMeterRegistry(),
    ) { clock }

    private val insight by lazy { service() }

    private fun rows(): List<StockReason> = repository.findAll().sortedBy { it.id }

    private fun failWith(vararg kinds: Kind) = kinds.forEach { k -> grounds += { throw VertexException(k, "ground ${k.label}") } }

    @BeforeEach
    fun 빈_테이블() {
        jdbc.update("DELETE FROM stock_reason")
    }

    @Nested
    inner class 사유_만들기 {
        @Test
        fun `새로 들어온 주도주의 사유와 기사를 남긴다`() {
            insight.run(Region.KR, kst(10, 15)) shouldBe 1

            val r = rows().single()
            listOf(r.region, r.tradingDay, r.code, r.name) shouldBe listOf(Region.KR, today, "011070", "LG이노텍")
            Triple(r.trigger, r.published, r.explained) shouldBe Triple(Trigger.ENTRY, true, true)
            r.reason shouldBe "美 라이다 업체 Aeva 지분가치 상승"
            r.keywords.values shouldBe listOf("Aeva")
            r.evidence.items shouldBe listOf(Article("파이낸셜뉴스", "LG이노텍, 美라이다 기업 지분가치 상승 영향", "https://fnnews.com/1"))
            r.related.items shouldBe listOf(Article("jkn.co.kr", "LG이노텍, 호재성 뉴스 부재 속 급등", "https://jkn.co.kr/2"))
        }

        @Test
        fun `기사는 출처·제목·주소 JSON으로 남는다`() {
            insight.run(Region.KR, kst(10, 15))

            json.readTree(jdbc.queryForObject("SELECT evidence FROM stock_reason", String::class.java)) shouldBe
                json.readTree("""[{"source":"파이낸셜뉴스","title":"LG이노텍, 美라이다 기업 지분가치 상승 영향","url":"https://fnnews.com/1"}]""")
        }

        @Test
        fun `기준 시각은 실행 시작이 아니라 그 종목을 다 만든 때다`() {
            every { vertex.structure(any(), any()) } answers {
                clock = Duration.ofSeconds(15)
                json.readTree(structured) to Usage()
            }

            insight.run(Region.KR, kst(10, 15))

            rows()[0].generatedAt shouldBe LocalDateTime.of(2026, 9, 28, 10, 15, 15)
        }

        @Test
        fun `검색 프롬프트에 직전 거래일 장 마감 이후라고 적는다`() {
            insight.run(Region.KR, kst(10, 15))

            prompts.single() shouldContain "2026-09-25 15:30 KST 이후"
        }

        @Test
        fun `생성 시간 밖에는 주도주도 묻지 않는다`() {
            insight.run(Region.KR, kst(8, 10)) shouldBe 0

            verify(exactly = 0) { domestic.findLeaders(any()) }
        }

        @Test
        fun `이미 만든 종목은 정해진 시각까지 다시 부르지 않는다`() {
            listOf(kst(10, 15), kst(10, 40), kst(11, 0)).forEach { insight.run(Region.KR, it) }

            prompts.size shouldBe 2
            rows().map { it.trigger } shouldBe listOf(Trigger.ENTRY, Trigger.SCHEDULED)
        }

        @Test
        fun `Vertex가 아파도 행을 남기고 1분 5분 뒤에 다시 한다`() {
            failWith(Kind.UNAVAILABLE, Kind.TIMEOUT)

            listOf(kst(10, 15), kst(10, 16), kst(10, 18)).forEach { insight.run(Region.KR, it) }

            rows().map { Triple(it.trigger, it.published, it.failure) } shouldBe listOf(Triple(Trigger.ENTRY, false, Failure.UNAVAILABLE), Triple(Trigger.RETRY, false, Failure.TIMEOUT))
            rows()[0].error!! shouldContain "unavailable"
        }

        @Test
        fun `설정이 틀린 오류는 다시 하지 않고 재배포 전까지 Vertex를 부르지 않는다`() {
            failWith(Kind.CLIENT_ERROR)
            insight.run(Region.KR, kst(10, 15))
            leaders = listOf(stock("011070", "LG이노텍"), stock("009830", "한화솔루션"))

            insight.run(Region.KR, kst(11, 0)) shouldBe 0

            prompts.size shouldBe 1
            rows().map { it.failure?.value } shouldBe listOf("client_error")
        }

        @Test
        fun `일시 장애가 세 번 이어지면 서킷이 열려 남은 종목은 행 없이 건너뛴다`() {
            insight.maxConcurrency = 1
            failWith(*Array(7) { Kind.TIMEOUT })
            leaders = (1..7).map { stock("00000$it", "종목$it") }

            insight.run(Region.KR, kst(10, 15)) shouldBe 3
            insight.run(Region.KR, kst(10, 17)) shouldBe 0

            prompts.size shouldBe 3
            rows().size shouldBe 3
        }

        @Test
        fun `서킷이 열린 뒤엔 한 종목으로 시험하고 성공하면 건너뛴 종목을 만든다`() {
            insight.maxConcurrency = 1
            failWith(Kind.TIMEOUT, Kind.TIMEOUT, Kind.TIMEOUT)
            leaders = (1..4).map { stock("00000$it", "종목$it") }

            insight.run(Region.KR, kst(10, 15)) shouldBe 3
            insight.run(Region.KR, kst(10, 21)) shouldBe 1
            insight.run(Region.KR, kst(10, 22)) shouldBe 3

            val published = rows().filter { it.published }
            published.map { it.code }.sorted() shouldBe listOf("000001", "000002", "000003", "000004")
            published.map { it.trigger.value }.sorted() shouldBe listOf("entry", "retry", "retry", "retry")
        }

        @Test
        fun `하루 상한에 닿으면 그날은 멈춘다`() {
            dailyLimit = 1
            leaders = listOf(stock("011070", "LG이노텍"), stock("009830", "한화솔루션"))

            insight.run(Region.KR, kst(10, 15)) shouldBe 1

            rows().size shouldBe 1
        }

        @Test
        fun `5퍼센트 넘게 오른 후보는 새로 들어올 때만 만든다`() {
            candidates = listOf(stock("011070", "LG이노텍"), stock("009830", "한화솔루션"))

            insight.run(Region.KR, kst(10, 15))
            insight.run(Region.KR, kst(11, 0))

            val made = rows().map { it.code to it.trigger.value }
            made.take(2).sortedBy { it.first } shouldBe listOf("009830" to "entry", "011070" to "entry")
            made.drop(2) shouldBe listOf("011070" to "scheduled")
        }

        @Test
        fun `여러 종목을 동시에 만들어 뒤 종목이 앞 종목을 기다리지 않는다`() {
            leaders = listOf(stock("011070", "LG이노텍"), stock("009830", "한화솔루션"))
            val meet = CyclicBarrier(2)
            repeat(2) { grounds += { meet.await(5, TimeUnit.SECONDS); answer } }

            insight.run(Region.KR, kst(10, 15)) shouldBe 2

            rows().all { it.published } shouldBe true
        }
    }

    @Nested
    inner class 화면에_내줄_사유 {
        @Test
        fun `종목마다 가장 최근의 설명된 사유 하나`() {
            insight.run(Region.KR, kst(10, 15))
            structured = """{"explained":false,"keywords":[],"reason":"","evidence":[],"related":[2]}"""
            insight.run(Region.KR, kst(11, 0))

            val shown = insight.reasons(Region.KR, kst(12, 0)).single()

            (shown.explained to shown.reason) shouldBe (true to "美 라이다 업체 Aeva 지분가치 상승")
        }

        @Test
        fun `프리마켓 시작 전에는 직전 거래일 사유를 내준다`() {
            insight.run(Region.KR, kst(10, 15, LocalDate.of(2026, 9, 25)))

            insight.reasons(Region.KR, kst(7, 59)).map { it.code } shouldBe listOf("011070")
            insight.reasons(Region.KR, kst(8, 0)).shouldBeEmpty()
        }
    }
}
