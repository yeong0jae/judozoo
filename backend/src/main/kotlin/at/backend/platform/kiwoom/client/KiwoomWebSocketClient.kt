package at.backend.platform.kiwoom.client

import at.backend.market.domain.PriceTick
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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

/**
 * Kiwoom 단일 WebSocket. 주문체결(type=00) + 실시간 시세(type=0B)를 같은 connection으로 수신.
 *
 * Kiwoom 모의(mockapi.kiwoom.com)는 토큰당 WS 1개만 허용 — 두 connection을 띄우면 두 번째가
 * 첫 번째를 kick out(SYSTEM 메시지 + code=1000 close) → 무한 재연결 핑퐁. 한 연결에서 REG 메시지의
 * data 배열에 여러 {item, type} 페어를 넣어 동시 구독.
 *
 * 연결 시퀀스:
 *   1. WebSocket 연결 → LOGIN
 *   2. LOGIN 성공 시 현재까지 누적된 구독(execution flag, price code 집합)을 한 번에 REG
 *   3. 이후 subscribePrice/unsubscribePrice는 REG/REMOVE 메시지로 증분 갱신
 */
class KiwoomWebSocketClient(
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

    /** 주문체결(type=00) 구독 여부 — 켜지면 LOGIN 후 자동 REG. */
    private val executionSubscribed = AtomicBoolean(false)

    /** 시세(type=0B) 구독 종목코드 집합 — LOGIN 후 자동 REG, 종목 추가/제거 시 증분 갱신. */
    private val priceSubscriptions = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var reconnectAttempt: Int = 0

    private val _executionNotices = MutableSharedFlow<ExecutionNotice>(extraBufferCapacity = 256)
    val executionNotices: SharedFlow<ExecutionNotice> = _executionNotices.asSharedFlow()

    private val _priceTicks = MutableSharedFlow<PriceTick>(extraBufferCapacity = 1024)
    val priceTicks: SharedFlow<PriceTick> = _priceTicks.asSharedFlow()

    fun subscribeExecution() {
        if (!executionSubscribed.compareAndSet(false, true)) return
        val session = currentSession.get()
        if (session != null && session.isOpen && loggedIn.get()) {
            sendReg(session, executionItems = true, priceCodes = emptyList())
        } else {
            connect()
        }
    }

    fun subscribePrice(stockCode: String) {
        if (!priceSubscriptions.add(stockCode)) return
        val session = currentSession.get()
        if (session != null && session.isOpen && loggedIn.get()) {
            sendReg(session, executionItems = false, priceCodes = listOf(stockCode))
        } else {
            connect()
        }
    }

    fun unsubscribePrice(stockCode: String) {
        if (!priceSubscriptions.remove(stockCode)) return
        currentSession.get()?.takeIf { it.isOpen && loggedIn.get() }
            ?.let { sendRemove(it, listOf(stockCode)) }
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
                    log.warn { "Kiwoom WS REG 실패 msg=${node.path("return_msg").asText()}" }
                }
                "REAL" -> handleRealFrame(node)
                "SYSTEM" -> log.warn { "Kiwoom WS SYSTEM 메시지(중복 세션 kick 가능) — 본문=$payload" }
            }
        }

        override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
            log.warn(exception) { "Kiwoom WS 전송 오류" }
        }

        override fun afterConnectionClosed(session: WebSocketSession, closeStatus: CloseStatus) {
            log.warn { "Kiwoom WS 끊김 status=$closeStatus, 재연결 예약" }
            currentSession.compareAndSet(session, null)
            connecting.set(false)
            loggedIn.set(false)
            if (executionSubscribed.get() || priceSubscriptions.isNotEmpty()) scheduleReconnect()
        }

        override fun supportsPartialMessages(): Boolean = false
    }

    private fun handleLoginResponse(session: WebSocketSession, node: JsonNode) {
        if (node.path("return_code").asInt(-1) != 0) {
            log.warn { "Kiwoom WS LOGIN 실패 msg=${node.path("return_msg").asText()}" }
            session.close()
            return
        }
        log.info { "Kiwoom WS LOGIN 성공 — execution=${executionSubscribed.get()}, prices=${priceSubscriptions.size}" }
        loggedIn.set(true)
        sendReg(session, executionItems = executionSubscribed.get(), priceCodes = priceSubscriptions.toList())
    }

    /** 활성화된 구독을 한 REG 메시지에 묶어 발사. data 배열에 type별 entry 추가. */
    private fun sendReg(session: WebSocketSession, executionItems: Boolean, priceCodes: List<String>) {
        val data = mutableListOf<Map<String, Any>>()
        if (executionItems) {
            data += mapOf("item" to listOf(""), "type" to listOf(EXECUTION_TYPE))
        }
        if (priceCodes.isNotEmpty()) {
            data += mapOf("item" to priceCodes, "type" to listOf(PRICE_TICK_TYPE))
        }
        if (data.isEmpty()) return
        val msg = mapOf(
            "trnm" to "REG",
            "grp_no" to "1",
            "refresh" to "1",
            "data" to data,
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
            when (item.path("type").asText()) {
                EXECUTION_TYPE -> parseExecutionNotice(item)?.let { _executionNotices.tryEmit(it) }
                PRICE_TICK_TYPE -> parsePriceTick(item)?.let { _priceTicks.tryEmit(it) }
            }
        }
    }

    private fun parseExecutionNotice(item: JsonNode): ExecutionNotice? {
        val values = item.path("values")
        if (values.path("913").asText() != "체결") return null
        val orderNo = values.path("9203").asText().takeIf { it.isNotBlank() } ?: return null
        val stockCode = values.path("9001").asText().takeIf { it.isNotBlank() } ?: return null
        val side = when (values.path("907").asText()) {
            "1" -> OrderSide.SELL
            "2" -> OrderSide.BUY
            else -> return null
        }
        val price = parseSignedInt(values.path("910").asText()) ?: return null
        // 915=단위체결량(이번 체결분). 911(체결량)은 누적값이라 누적 가산하면 분할 체결 시 중복된다.
        val qty = values.path("915").asText().toIntOrNull()?.takeIf { it > 0 } ?: return null
        return ExecutionNotice(
            orderNo = orderNo,
            stockCode = stockCode,
            side = side,
            executedQty = qty,
            executedPrice = price,
            timestamp = Instant.now(),
        )
    }

    private fun parsePriceTick(item: JsonNode): PriceTick? {
        val stockCode = item.path("item").asText().takeIf { it.isNotBlank() } ?: return null
        val price = parseSignedInt(item.path("values").path("10").asText()) ?: return null
        return PriceTick(stockCode = stockCode, price = price, timestamp = Instant.now())
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
        executionSubscribed.set(false)
        priceSubscriptions.clear()
        runCatching { currentSession.getAndSet(null)?.close() }
    }

    companion object {
        private const val EXECUTION_TYPE = "00"
        private const val PRICE_TICK_TYPE = "0B"
        private val BACKOFF_DELAYS_SEC = longArrayOf(1, 2, 5, 5)
    }
}
