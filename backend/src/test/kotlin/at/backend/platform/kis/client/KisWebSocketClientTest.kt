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

/**
 * H0STCNI0 응답 본문 빌더 — KIS docs 필드 순서대로 14개 슬롯 채움.
 * 0:CUST_ID 1:ACNT_NO 2:ODER_NO 3:OODER_NO 4:SELN_BYOV_CLS 5:RCTF_CLS
 * 6:ODER_KIND 7:ODER_COND 8:STCK_SHRN_ISCD 9:CNTG_QTY 10:CNTG_UNPR
 * 11:STCK_CNTG_HOUR 12:RFUS_YN 13:CNTG_YN
 */
private fun executionFields(
    orderNo: String,
    side: String,
    stockCode: String,
    qty: String,
    price: String,
    rfusYn: String,
    cntgYn: String,
): String = listOf(
    "_",        // 0 CUST_ID
    "_",        // 1 ACNT_NO
    orderNo,    // 2 ODER_NO
    "_",        // 3 OODER_NO
    side,       // 4 SELN_BYOV_CLS
    "_",        // 5 RCTF_CLS
    "_",        // 6 ODER_KIND
    "_",        // 7 ODER_COND
    stockCode,  // 8 STCK_SHRN_ISCD
    qty,        // 9 CNTG_QTY
    price,      // 10 CNTG_UNPR
    "_",        // 11 STCK_CNTG_HOUR
    rfusYn,     // 12 RFUS_YN
    cntgYn,     // 13 CNTG_YN
).joinToString("^")

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
            // KIS docs 기준 0-indexed: [2]ODER_NO [4]SELN_BYOV_CLS [8]STCK_SHRN_ISCD
            //                          [9]CNTG_QTY [10]CNTG_UNPR [12]RFUS_YN [13]CNTG_YN
            val body = executionFields(
                orderNo = "0000123456",
                side = "02",        // 매수
                stockCode = "005930",
                qty = "10",
                price = "70000",
                rfusYn = "0",       // 승인
                cntgYn = "2",       // 체결
            )
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

        test("H0STCNI0 접수통보(CNTG_YN=1)와 거부(RFUS_YN=1)는 무시되고 체결만 emit된다") {
            val (client, session, _) = fixture(mapper)
            val rejected = executionFields(
                orderNo = "REJECT", side = "02", stockCode = "005930",
                qty = "10", price = "70000",
                rfusYn = "1",       // 거부 — 무시
                cntgYn = "2",
            )
            val acknowledged = executionFields(
                orderNo = "ACK", side = "02", stockCode = "005930",
                qty = "10", price = "70000", rfusYn = "0",
                cntgYn = "1",       // 접수통보 — 무시
            )
            val executed = executionFields(
                orderNo = "FILL", side = "02", stockCode = "005930",
                qty = "10", price = "70000", rfusYn = "0",
                cntgYn = "2",       // 체결 — emit
            )

            val notice = coroutineScope {
                val deferred = async { withTimeout(1000.milliseconds) { client.executionNotices.first() } }
                delay(50.milliseconds)
                client.handler.handleMessage(session, TextMessage("0|H0STCNI0|001|$rejected"))
                client.handler.handleMessage(session, TextMessage("0|H0STCNI0|001|$acknowledged"))
                client.handler.handleMessage(session, TextMessage("0|H0STCNI0|001|$executed"))
                deferred.await()
            }
            // 거부/접수통보가 모두 무시되고 첫 emit은 체결만
            notice.kisOrderNo shouldBe "FILL"
        }

        test("SUBSCRIBE SUCCESS 응답의 iv/key를 받아 이후 암호화 H0STCNI0 프레임을 복호화한다") {
            val (client, session, _) = fixture(mapper)
            // 32바이트 key + 16바이트 iv (AES-256-CBC)
            val key = "0123456789abcdef0123456789abcdef"
            val iv = "0123456789abcdef"
            val subscribeAck = """{"header":{"tr_id":"H0STCNI0"},"body":{"output":{"key":"$key","iv":"$iv"}}}"""
            client.handler.handleMessage(session, TextMessage(subscribeAck))

            val plaintext = executionFields(
                orderNo = "ENC123", side = "01", stockCode = "005930",
                qty = "5", price = "80000", rfusYn = "0", cntgYn = "2",
            )
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                javax.crypto.Cipher.ENCRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(key.toByteArray(), "AES"),
                javax.crypto.spec.IvParameterSpec(iv.toByteArray()),
            )
            val encrypted = java.util.Base64.getEncoder()
                .encodeToString(cipher.doFinal(plaintext.toByteArray()))
            val frame = "1|H0STCNI0|001|$encrypted"

            val notice = coroutineScope {
                val deferred = async { withTimeout(1000.milliseconds) { client.executionNotices.first() } }
                delay(50.milliseconds)
                client.handler.handleMessage(session, TextMessage(frame))
                deferred.await()
            }
            notice.kisOrderNo shouldBe "ENC123"
            notice.side shouldBe "SELL"
            notice.executedQty shouldBe 5
            notice.executedPrice shouldBe 80_000
        }

        test("필드 수가 부족한 H0STCNT0 프레임은 예외 없이 무시된다") {
            val (client, session, sent) = fixture(mapper)
            val frame = "0|H0STCNT0|001|005930"

            client.handler.handleMessage(session, TextMessage(frame))
            sent shouldHaveSize 0
        }
    }
})
