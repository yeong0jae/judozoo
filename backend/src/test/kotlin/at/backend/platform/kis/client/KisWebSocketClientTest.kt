package at.backend.platform.kis.client

import at.backend.platform.kis.KisApprovalKeyProvider
import at.backend.platform.kis.config.KisProperties
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.WebSocketClient
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.milliseconds

private data class Fixture(
    val client: KisWebSocketClient,
    val session: WebSocketSession,
    val sent: MutableList<String>,
)

private fun fixture(mapper: JsonMapper): Fixture {
    val properties = mockk<KisProperties>(relaxed = true).apply {
        every { wsUrl } returns "ws://localhost:9999"
    }
    val approvalProvider = mockk<KisApprovalKeyProvider>().apply {
        every { approvalKey } returns "fake-approval-key"
    }
    val sent = mutableListOf<String>()
    val session = mockk<WebSocketSession>(relaxed = true).apply {
        every { id } returns "session-1"
        every { isOpen } returns true
        every { sendMessage(any()) } answers {
            val msg = firstArg<TextMessage>()
            sent.add(msg.payload)
        }
    }
    val wsClient = mockk<WebSocketClient>().apply {
        every { execute(any(), any<String>()) } returns CompletableFuture.completedFuture(session)
    }
    val client = KisWebSocketClient(properties, approvalProvider, wsClient, mapper)
    return Fixture(client, session, sent)
}

class KisWebSocketClientTest : FunSpec({

    val mapper = JsonMapper()

    context("구독 메시지 포맷") {
        test("subscribePrice는 H0STCNT0 + tr_type=1 페이로드를 전송한다") {
            val (client, _, sent) = fixture(mapper)
            client.subscribePrice("005930")

            sent shouldHaveSize 1
            val payload = mapper.readTree(sent[0])
            payload["header"]["approval_key"].asText() shouldBe "fake-approval-key"
            payload["header"]["custtype"].asText() shouldBe "P"
            payload["header"]["tr_type"].asText() shouldBe "1"
            payload["body"]["input"]["tr_id"].asText() shouldBe "H0STCNT0"
            payload["body"]["input"]["tr_key"].asText() shouldBe "005930"
        }

        test("unsubscribePrice는 tr_type=2 페이로드를 전송한다") {
            val (client, _, sent) = fixture(mapper)
            client.subscribePrice("005930")
            sent.clear()

            client.unsubscribePrice("005930")

            sent shouldHaveSize 1
            val payload = mapper.readTree(sent[0])
            payload["header"]["tr_type"].asText() shouldBe "2"
            payload["body"]["input"]["tr_id"].asText() shouldBe "H0STCNT0"
        }

        test("subscribeExecutionNotice는 H0STCNI0 페이로드를 htsId로 전송한다") {
            val (client, _, sent) = fixture(mapper)
            client.subscribeExecutionNotice("HTSID01")

            val payload = mapper.readTree(sent[0])
            payload["body"]["input"]["tr_id"].asText() shouldBe "H0STCNI0"
            payload["body"]["input"]["tr_key"].asText() shouldBe "HTSID01"
        }

        test("같은 종목을 두 번 구독해도 메시지는 한 번만 전송된다") {
            val (client, _, sent) = fixture(mapper)
            client.subscribePrice("005930")
            client.subscribePrice("005930")

            sent shouldHaveSize 1
        }
    }

    context("PINGPONG 에코") {
        test("PINGPONG JSON 수신 시 동일 페이로드를 응답한다") {
            val (client, session, sent) = fixture(mapper)
            val ping = """{"header":{"tr_id":"PINGPONG"},"body":{}}"""

            client.handler.handleMessage(session, TextMessage(ping))

            sent shouldHaveSize 1
            sent[0] shouldBe ping
        }
    }

    context("연결 상태 이벤트") {
        test("afterConnectionEstablished 호출 시 connectionState=true 발행") {
            val (client, session, _) = fixture(mapper)

            client.handler.afterConnectionEstablished(session)

            withTimeout(1000.milliseconds) { client.connectionState.first() } shouldBe true
        }

        test("afterConnectionClosed 호출 시 connectionState=false 발행") {
            val (client, session, _) = fixture(mapper)
            client.handler.afterConnectionEstablished(session)

            client.handler.afterConnectionClosed(session, CloseStatus.NORMAL)

            withTimeout(1000.milliseconds) { client.connectionState.first() } shouldBe false
        }
    }

    context("실시간 프레임 파싱") {
        // 필드 인덱스는 KIS 공식 문서 기반 추정. 실서버 응답 일치 검증은 Phase 7 sanity check.
        test("H0STCNT0 평문 프레임에서 PriceTick을 추출한다") {
            val (client, session, _) = fixture(mapper)
            val frame = "0|H0STCNT0|001|005930^_^70000"

            val tick = coroutineScope {
                val deferred = async { withTimeout(1000.milliseconds) { client.priceTicks.first() } }
                delay(50.milliseconds)
                client.handler.handleMessage(session, TextMessage(frame))
                deferred.await()
            }
            tick.stockCode shouldBe "005930"
            tick.price shouldBe 70_000
        }

        test("H0STCNI0 평문 프레임에서 ExecutionNotice를 추출한다") {
            val (client, session, _) = fixture(mapper)
            // [2]=주문번호, [4]=매도매수구분(02=매수), [7]=체결단가, [12]=체결수량, [15]=종목코드
            val body = listOf(
                "_",
                "_",
                "0000123456",
                "_",
                "02",
                "_",
                "_",
                "70000",
                "_",
                "_",
                "_",
                "_",
                "10",
                "_",
                "_",
                "005930"
            )
                .joinToString("^")
            val frame = "0|H0STCNI0|001|$body"

            val notice = coroutineScope {
                val deferred = async { withTimeout(1000.milliseconds) { client.executionNotices.first() } }
                delay(50.milliseconds)
                client.handler.handleMessage(session, TextMessage(frame))
                deferred.await()
            }
            notice.kisOrderNo shouldBe "0000123456"
            notice.side shouldBe "BUY"
            notice.executedPrice shouldBe 70_000
            notice.executedQty shouldBe 10
            notice.stockCode shouldBe "005930"
        }

        test("필드 수가 부족한 H0STCNT0 프레임은 예외 없이 무시된다") {
            val (client, session, sent) = fixture(mapper)
            val frame = "0|H0STCNT0|001|005930"

            client.handler.handleMessage(session, TextMessage(frame))
            sent shouldHaveSize 0
        }
    }
})
