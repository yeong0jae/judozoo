package at.backend.platform.kis.client

import at.backend.platform.kis.KisApprovalKeyProvider
import at.backend.platform.kis.config.KisProperties
import at.backend.trading.domain.order.ExecutionNotice
import at.backend.trading.domain.price.PriceTick
import tools.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.slf4j.LoggerFactory
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.WebSocketMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.WebSocketClient
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class KisWebSocketClient(
    private val properties: KisProperties,
    private val approvalKeyProvider: KisApprovalKeyProvider,
    private val webSocketClient: WebSocketClient,
    private val objectMapper: ObjectMapper,
) : WebSocketHandler {

    private val log = LoggerFactory.getLogger(javaClass)
    private val sessionRef = AtomicReference<WebSocketSession?>(null)
    private val subscriptions = ConcurrentHashMap.newKeySet<Subscription>()
    private val reconnectExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "kis-ws-reconnect").apply { isDaemon = true } }

    @Volatile private var reconnectAttempt: Int = 0

    private val _priceTicks = MutableSharedFlow<PriceTick>(extraBufferCapacity = 1024)
    private val _executionNotices = MutableSharedFlow<ExecutionNotice>(extraBufferCapacity = 256)

    val priceTicks: SharedFlow<PriceTick> = _priceTicks.asSharedFlow()
    val executionNotices: SharedFlow<ExecutionNotice> = _executionNotices.asSharedFlow()

    fun subscribePrice(stockCode: String) {
        val sub = Subscription(TR_PRICE, stockCode)
        if (!subscriptions.add(sub)) return
        ensureConnected { session -> sendSubscription(session, sub, subscribe = true) }
    }

    fun unsubscribePrice(stockCode: String) {
        val sub = Subscription(TR_PRICE, stockCode)
        if (!subscriptions.remove(sub)) return
        sessionRef.get()?.let { sendSubscription(it, sub, subscribe = false) }
    }

    fun subscribeExecutionNotice(htsId: String) {
        val sub = Subscription(TR_EXEC, htsId)
        if (!subscriptions.add(sub)) return
        ensureConnected { session -> sendSubscription(session, sub, subscribe = true) }
    }

    private fun ensureConnected(afterConnect: (WebSocketSession) -> Unit) {
        val current = sessionRef.get()
        if (current != null && current.isOpen) {
            afterConnect(current)
            return
        }
        connect()?.let(afterConnect)
    }

    private fun connect(): WebSocketSession? {
        return try {
            val session = webSocketClient.execute(this, properties.wsUrl)
                .get(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            sessionRef.set(session)
            session
        } catch (e: Exception) {
            log.warn("KIS WS 연결 실패, 백오프 재연결 예약", e)
            scheduleReconnect()
            null
        }
    }

    private fun sendSubscription(session: WebSocketSession, sub: Subscription, subscribe: Boolean) {
        val payload = mapOf(
            "header" to mapOf(
                "approval_key" to approvalKeyProvider.approvalKey,
                "custtype" to "P",
                "tr_type" to if (subscribe) "1" else "2",
                "content-type" to "utf-8",
            ),
            "body" to mapOf(
                "input" to mapOf("tr_id" to sub.trId, "tr_key" to sub.trKey),
            ),
        )
        session.sendMessage(TextMessage(objectMapper.writeValueAsString(payload)))
    }

    override fun afterConnectionEstablished(session: WebSocketSession) {
        log.info("KIS WS 연결됨: sessionId={}", session.id)
        reconnectAttempt = 0
        subscriptions.forEach { sendSubscription(session, it, subscribe = true) }
    }

    override fun handleMessage(session: WebSocketSession, message: WebSocketMessage<*>) {
        val payload = message.payload as? String ?: return
        if (payload.startsWith("{")) {
            handleJsonMessage(session, payload)
        } else {
            handleRealtimeFrame(payload)
        }
    }

    private fun handleJsonMessage(session: WebSocketSession, payload: String) {
        val node = objectMapper.readTree(payload)
        val trId = node.path("header").path("tr_id").asText()
        if (trId == "PINGPONG") {
            session.sendMessage(TextMessage(payload))
        }
    }

    private fun handleRealtimeFrame(payload: String) {
        val parts = payload.split("|", limit = 4)
        if (parts.size < 4) return
        val trId = parts[1]
        val body = parts[3]
        // TODO Phase 7 sanity check: 실서버 응답으로 필드 인덱스 / 암호화 처리 검증
        when (trId) {
            TR_PRICE -> parsePriceTick(body)?.let { _priceTicks.tryEmit(it) }
            TR_EXEC -> parseExecutionNotice(body)?.let { _executionNotices.tryEmit(it) }
        }
    }

    private fun parsePriceTick(body: String): PriceTick? {
        val fields = body.split("^")
        if (fields.size < 3) return null
        val stockCode = fields[0].takeIf { it.isNotBlank() } ?: return null
        val price = fields[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return PriceTick(stockCode = stockCode, price = price, timestamp = Instant.now())
    }

    private fun parseExecutionNotice(body: String): ExecutionNotice? {
        val fields = body.split("^")
        if (fields.size < 16) return null
        val sideCode = fields[4]
        val side = when (sideCode) { "02" -> "BUY"; "01" -> "SELL"; else -> return null }
        val orderNo = fields[2].takeIf { it.isNotBlank() } ?: return null
        val price = fields[7].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val qty = fields[12].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val stockCode = fields[15].takeIf { it.isNotBlank() } ?: return null
        return ExecutionNotice(
            kisOrderNo = orderNo,
            stockCode = stockCode,
            side = side,
            executedQty = qty,
            executedPrice = price,
            timestamp = Instant.now(),
        )
    }

    override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
        log.warn("KIS WS 전송 오류", exception)
    }

    override fun afterConnectionClosed(session: WebSocketSession, closeStatus: CloseStatus) {
        log.warn("KIS WS 끊김 status={}, 재연결 예약", closeStatus)
        sessionRef.compareAndSet(session, null)
        if (subscriptions.isNotEmpty()) scheduleReconnect()
    }

    override fun supportsPartialMessages(): Boolean = false

    private fun scheduleReconnect() {
        val delay = BACKOFF_DELAYS_SEC[reconnectAttempt.coerceIn(0, BACKOFF_DELAYS_SEC.size - 1)]
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(BACKOFF_DELAYS_SEC.size - 1)
        reconnectExecutor.schedule({ connect() }, delay, TimeUnit.SECONDS)
    }

    @PreDestroy
    fun shutdown() {
        runCatching { sessionRef.getAndSet(null)?.close() }
        reconnectExecutor.shutdownNow()
    }

    private data class Subscription(val trId: String, val trKey: String)

    companion object {
        private const val TR_PRICE = "H0STCNT0"
        private const val TR_EXEC = "H0STCNI0"
        private const val CONNECT_TIMEOUT_SEC = 5L
        private val BACKOFF_DELAYS_SEC = longArrayOf(1, 2, 5, 5)
    }
}
