package at.backend.platform.kiwoom.client

import at.backend.market.domain.PriceTick
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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

/**
 * Kiwoom 실시간 시세 (주식체결, type=0B) 수신. 종목별 동적 구독/해지.
 *
 * 연결 시퀀스:
 *   1. WebSocket TLS 연결
 *   2. LOGIN
 *   3. 구독 누적된 종목들에 대해 REG type=0B 전송 (재연결 시에도 동일)
 *
 * 추후 종목 추가는 `subscribePrice(code)` → REG (refresh=1로 누적 유지),
 * 해지는 `unsubscribePrice(code)` → REMOVE.
 */
class KiwoomPriceTickWebSocketClient(
    private val wsUrl: String,
    private val authClient: KiwoomAuthClient,
    private val webSocketClient: WebSocketClient,
    private val objectMapper: ObjectMapper,
    private val applicationScope: CoroutineScope,
) {
    private val log = KotlinLogging.logger {}

    private val currentSession = AtomicReference<WebSocketSession?>(null)
    private val connecting = AtomicBoolean(false)
    private val loggedIn = AtomicBoolean(false)
    private val subscribed = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var reconnectAttempt: Int = 0

    private val _priceTicks = MutableSharedFlow<PriceTick>(extraBufferCapacity = 1024)
    val priceTicks: SharedFlow<PriceTick> = _priceTicks.asSharedFlow()

    fun subscribe(stockCode: String) {
        if (!subscribed.add(stockCode)) return
        val session = currentSession.get()
        if (session != null && session.isOpen && loggedIn.get()) {
            sendReg(session, listOf(stockCode))
        } else {
            connect()
        }
    }

    fun unsubscribe(stockCode: String) {
        if (!subscribed.remove(stockCode)) return
        currentSession.get()?.takeIf { it.isOpen && loggedIn.get() }
            ?.let { sendRemove(it, listOf(stockCode)) }
    }

    private fun connect() {
        if (!connecting.compareAndSet(false, true)) return
        try {
            log.info { "Kiwoom 시세 WS 연결 시도 → $wsUrl" }
            webSocketClient.execute(handler, wsUrl)
                .whenComplete { _, err ->
                    if (err != null) {
                        log.warn(err) { "Kiwoom 시세 WS 연결 실패, 백오프 재연결 예약" }
                        connecting.set(false)
                        scheduleReconnect()
                    }
                }
        } catch (e: Exception) {
            log.warn(e) { "Kiwoom 시세 WS execute 호출 자체 실패" }
            connecting.set(false)
            scheduleReconnect()
        }
    }

    internal val handler: WebSocketHandler = object : WebSocketHandler {
        override fun afterConnectionEstablished(session: WebSocketSession) {
            log.info { "Kiwoom 시세 WS 연결됨: sessionId=${session.id}" }
            currentSession.set(session)
            connecting.set(false)
            reconnectAttempt = 0
            loggedIn.set(false)
            val token = authClient.getAccessToken()
            session.sendMessage(TextMessage(objectMapper.writeValueAsString(mapOf("trnm" to "LOGIN", "token" to token))))
        }

        override fun handleMessage(session: WebSocketSession, message: WebSocketMessage<*>) {
            val payload = message.payload as? String ?: return
            val node = runCatching { objectMapper.readTree(payload) }.getOrNull() ?: return
            when (node.path("trnm").asText()) {
                "LOGIN" -> handleLoginResponse(session, node)
                "PING" -> session.sendMessage(TextMessage(payload))
                "REG" -> if (node.path("return_code").asInt(-1) != 0) {
                    log.warn { "Kiwoom 시세 WS REG 실패 msg=${node.path("return_msg").asText()}" }
                }
                "REAL" -> handleRealFrame(node)
            }
        }

        override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
            log.warn(exception) { "Kiwoom 시세 WS 전송 오류" }
        }

        override fun afterConnectionClosed(session: WebSocketSession, closeStatus: CloseStatus) {
            log.warn { "Kiwoom 시세 WS 끊김 status=$closeStatus, 재연결 예약" }
            currentSession.compareAndSet(session, null)
            connecting.set(false)
            loggedIn.set(false)
            if (subscribed.isNotEmpty()) scheduleReconnect()
        }

        override fun supportsPartialMessages(): Boolean = false
    }

    private fun handleLoginResponse(session: WebSocketSession, node: JsonNode) {
        if (node.path("return_code").asInt(-1) != 0) {
            log.warn { "Kiwoom 시세 WS LOGIN 실패 msg=${node.path("return_msg").asText()}" }
            session.close()
            return
        }
        log.info { "Kiwoom 시세 WS LOGIN 성공 — REG 전송 (codes=${subscribed.size})" }
        loggedIn.set(true)
        if (subscribed.isNotEmpty()) sendReg(session, subscribed.toList())
    }

    private fun sendReg(session: WebSocketSession, codes: List<String>) {
        val msg = mapOf(
            "trnm" to "REG",
            "grp_no" to "1",
            "refresh" to "1",
            "data" to listOf(mapOf("item" to codes, "type" to listOf(PRICE_TICK_TYPE))),
        )
        session.sendMessage(TextMessage(objectMapper.writeValueAsString(msg)))
    }

    private fun sendRemove(session: WebSocketSession, codes: List<String>) {
        val msg = mapOf(
            "trnm" to "REMOVE",
            "grp_no" to "1",
            "data" to listOf(mapOf("item" to codes, "type" to listOf(PRICE_TICK_TYPE))),
        )
        session.sendMessage(TextMessage(objectMapper.writeValueAsString(msg)))
    }

    private fun handleRealFrame(node: JsonNode) {
        val data = node.path("data")
        if (!data.isArray) return
        for (item in data) {
            if (item.path("type").asText() != PRICE_TICK_TYPE) continue
            val stockCode = item.path("item").asText().takeIf { it.isNotBlank() } ?: continue
            // values["10"] = 현재가 (with +/- 부호)
            val priceText = item.path("values").path("10").asText()
            val price = parseSignedInt(priceText) ?: continue
            _priceTicks.tryEmit(
                PriceTick(stockCode = stockCode, price = price, timestamp = Instant.now())
            )
        }
    }

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
        runCatching { currentSession.getAndSet(null)?.close() }
    }

    companion object {
        private const val PRICE_TICK_TYPE = "0B"
        private val BACKOFF_DELAYS_SEC = longArrayOf(1, 2, 5, 5)
    }
}
