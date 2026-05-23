package at.backend.platform.kiwoom.client

import at.backend.trading.domain.order.ExecutionNotice
import at.backend.trading.domain.order.OrderSide
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.WebSocketMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.WebSocketClient
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

/**
 * Kiwoom 주문체결 통보 실시간 수신.
 *
 * 연결 시퀀스:
 *   1. WebSocket 연결 (TLS)
 *   2. `{trnm:"LOGIN", token:<access_token>}` 전송
 *   3. LOGIN 응답 return_code 0 확인
 *   4. `{trnm:"REG", grp_no:"1", refresh:"1", data:[{item:[""], type:["00"]}]}` 전송
 *   5. REAL 메시지 수신 (실시간 주문체결)
 *
 * 필터링: data[].values["913"] == "체결" 인 항목만 emit (접수/취소/거부 무시).
 * PING: trnm=="PING" 메시지는 동일 본문 echo back.
 */
class KiwoomExecutionWebSocketClient(
    private val wsUrl: String,
    private val authClient: KiwoomAuthClient,
    private val webSocketClient: WebSocketClient,
    private val objectMapper: ObjectMapper,
    private val applicationScope: CoroutineScope,
) {
    private val log = KotlinLogging.logger {}

    private val currentSession = AtomicReference<WebSocketSession?>(null)
    private val connecting = AtomicBoolean(false)
    private val subscribed = AtomicBoolean(false)

    @Volatile
    private var reconnectAttempt: Int = 0

    private val _executionNotices = MutableSharedFlow<ExecutionNotice>(extraBufferCapacity = 256)
    val executionNotices: SharedFlow<ExecutionNotice> = _executionNotices.asSharedFlow()

    fun subscribe() {
        if (!subscribed.compareAndSet(false, true)) return
        connect()
    }

    private fun connect() {
        if (!connecting.compareAndSet(false, true)) return
        try {
            log.info { "Kiwoom WS 연결 시도 → $wsUrl" }
            webSocketClient.execute(handler, wsUrl)
                .whenComplete { _, err ->
                    if (err != null) {
                        log.warn(err) { "Kiwoom WS 연결 실패, 백오프 재연결 예약" }
                        connecting.set(false)
                        scheduleReconnect()
                    }
                }
        } catch (e: Exception) {
            log.warn(e) { "Kiwoom WS execute 호출 자체 실패" }
            connecting.set(false)
            scheduleReconnect()
        }
    }

    internal val handler: WebSocketHandler = object : WebSocketHandler {
        override fun afterConnectionEstablished(session: WebSocketSession) {
            log.info { "Kiwoom WS 연결됨: sessionId=${session.id}" }
            currentSession.set(session)
            connecting.set(false)
            reconnectAttempt = 0
            // LOGIN 즉시 전송. REG는 LOGIN 성공 응답 수신 후 발사.
            val token = authClient.getAccessToken()
            val login = mapOf("trnm" to "LOGIN", "token" to token)
            session.sendMessage(TextMessage(objectMapper.writeValueAsString(login)))
        }

        override fun handleMessage(session: WebSocketSession, message: WebSocketMessage<*>) {
            val payload = message.payload as? String ?: return
            val node = runCatching { objectMapper.readTree(payload) }.getOrNull() ?: return
            when (node.path("trnm").asText()) {
                "LOGIN" -> handleLoginResponse(session, node)
                "PING" -> session.sendMessage(TextMessage(payload))           // echo back
                "REG" -> handleRegResponse(node)
                "REAL" -> handleRealFrame(node)
                else -> log.debug { "Kiwoom WS 미지원 trnm=${node.path("trnm").asText()}" }
            }
        }

        override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
            log.warn(exception) { "Kiwoom WS 전송 오류" }
        }

        override fun afterConnectionClosed(session: WebSocketSession, closeStatus: CloseStatus) {
            log.warn { "Kiwoom WS 끊김 status=$closeStatus, 재연결 예약" }
            currentSession.compareAndSet(session, null)
            connecting.set(false)
            if (subscribed.get()) scheduleReconnect()
        }

        override fun supportsPartialMessages(): Boolean = false
    }

    private fun handleLoginResponse(session: WebSocketSession, node: tools.jackson.databind.JsonNode) {
        val rc = node.path("return_code").asInt(-1)
        if (rc != 0) {
            log.warn { "Kiwoom WS LOGIN 실패 rc=$rc msg=${node.path("return_msg").asText()}" }
            session.close()
            return
        }
        log.info { "Kiwoom WS LOGIN 성공 — REG 전송" }
        val reg = mapOf(
            "trnm" to "REG",
            "grp_no" to "1",
            "refresh" to "1",
            "data" to listOf(
                mapOf("item" to listOf(""), "type" to listOf(EXECUTION_TYPE)),
            ),
        )
        session.sendMessage(TextMessage(objectMapper.writeValueAsString(reg)))
    }

    private fun handleRegResponse(node: tools.jackson.databind.JsonNode) {
        val rc = node.path("return_code").asInt(-1)
        if (rc != 0) {
            log.warn { "Kiwoom WS REG 실패 rc=$rc msg=${node.path("return_msg").asText()}" }
        } else {
            log.info { "Kiwoom WS REG 성공 (주문체결 구독)" }
        }
    }

    private fun handleRealFrame(node: tools.jackson.databind.JsonNode) {
        val data = node.path("data")
        if (!data.isArray) return
        for (item in data) {
            if (item.path("type").asText() != EXECUTION_TYPE) continue
            val values = item.path("values")
            // 913: 주문상태 — "체결"만 통과 (접수/취소/거부/확인 무시)
            if (values.path("913").asText() != "체결") continue
            val orderNo = values.path("9203").asText().takeIf { it.isNotBlank() } ?: continue
            val stockCode = values.path("9001").asText().takeIf { it.isNotBlank() } ?: continue
            // 907: 매도수구분 — "1":매도, "2":매수
            val side = when (values.path("907").asText()) {
                "1" -> OrderSide.SELL
                "2" -> OrderSide.BUY
                else -> continue
            }
            val price = parseSignedInt(values.path("910").asText()) ?: continue
            val qty = values.path("911").asText().toIntOrNull()?.takeIf { it > 0 } ?: continue
            _executionNotices.tryEmit(
                ExecutionNotice(
                    orderNo = orderNo,
                    stockCode = stockCode,
                    side = side,
                    executedQty = qty,
                    executedPrice = price,
                    timestamp = Instant.now(),
                )
            )
        }
    }

    /** Kiwoom 값은 부호(+/-) 접두 가능 — 부호 제거 후 절대값 Int. */
    private fun parseSignedInt(s: String): Int? {
        val cleaned = s.trim().removePrefix("+").removePrefix("-")
        return cleaned.toIntOrNull()?.takeIf { it > 0 }
    }

    private fun scheduleReconnect() {
        val delaySec = BACKOFF_DELAYS_SEC[reconnectAttempt.coerceIn(0, BACKOFF_DELAYS_SEC.size - 1)]
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(BACKOFF_DELAYS_SEC.size - 1)
        applicationScope.launch {
            delay(delaySec.seconds)
            connect()
        }
    }

    @PreDestroy
    fun shutdown() {
        subscribed.set(false)
        runCatching { currentSession.getAndSet(null)?.close() }
    }

    companion object {
        private const val EXECUTION_TYPE = "00"
        private val BACKOFF_DELAYS_SEC = longArrayOf(1, 2, 5, 5)
    }
}
