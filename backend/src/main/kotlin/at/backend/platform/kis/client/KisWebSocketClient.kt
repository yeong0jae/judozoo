package at.backend.platform.kis.client

import at.backend.market.domain.PriceTick
import at.backend.platform.kis.KisApprovalKeyProvider
import at.backend.platform.kis.client.payload.KisSubscribePayload
import at.backend.platform.kis.config.KisProperties
import at.backend.trading.domain.order.ExecutionNotice
import at.backend.trading.domain.order.OrderSide
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.web.socket.*
import org.springframework.web.socket.client.WebSocketClient
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.seconds

class KisWebSocketClient(
    private val properties: KisProperties,
    private val approvalKeyProvider: KisApprovalKeyProvider,
    private val webSocketClient: WebSocketClient,
    private val objectMapper: ObjectMapper,
    private val applicationScope: CoroutineScope,
) {

    private val log = KotlinLogging.logger {}
    private val trExec: String = properties.tr.executionNotice
    private val currentSession = AtomicReference<WebSocketSession?>(null)

    // execute() 호출 중복 방지. afterConnectionEstablished 또는 실패 시 해제.
    private val connecting = AtomicBoolean(false)

    // 종목에 대한 중복 구독 방지, 식별
    private val subscriptions = ConcurrentHashMap.newKeySet<Subscription>()

    // tr_id별 AES256 복호화 키 (구독 SUBSCRIBE SUCCESS 응답에서 추출)
    private val cipherKeys = ConcurrentHashMap<String, AesKey>()

    @Volatile
    private var reconnectAttempt: Int = 0

    private val _priceTicks = MutableSharedFlow<PriceTick>(extraBufferCapacity = 1024)
    private val _executionNotices = MutableSharedFlow<ExecutionNotice>(extraBufferCapacity = 256)
    private val _connectionState = MutableSharedFlow<Boolean>(replay = 1, extraBufferCapacity = 16)

    val priceTicks: SharedFlow<PriceTick> = _priceTicks.asSharedFlow()
    val executionNotices: SharedFlow<ExecutionNotice> = _executionNotices.asSharedFlow()
    val connectionState: SharedFlow<Boolean> = _connectionState.asSharedFlow()

    fun subscribePrice(stockCode: String) {
        val sub = Subscription(TR_PRICE, stockCode)
        if (!subscriptions.add(sub)) return
        ensureConnected { session -> sendSubscription(session, sub, subscribe = true) }
    }

    fun subscribeExecutionNotice() {
        val sub = Subscription(trExec, properties.htsId)
        if (!subscriptions.add(sub)) return
        ensureConnected { session -> sendSubscription(session, sub, subscribe = true) }
    }

    fun unsubscribePrice(stockCode: String) {
        val sub = Subscription(TR_PRICE, stockCode)
        if (!subscriptions.remove(sub)) return
        currentSession.get()?.let { sendSubscription(it, sub, subscribe = false) }
    }

    private fun ensureConnected(afterConnect: (WebSocketSession) -> Unit) {
        val current = currentSession.get()
        if (current != null && current.isOpen) {
            afterConnect(current)
            return
        }
        // 끊긴 상태: subscriptions 셋은 이미 호출자가 갱신함.
        // 신규 연결 후 afterConnectionEstablished가 셋의 모든 항목을 SUBSCRIBE 전송한다.
        connect()
    }

    /**
     * 비동기 연결 시작. Spring `WebSocketClient.execute(...)`가 반환하는 CompletableFuture는
     * 어댑터에 따라 세션 확립 후에도 즉시 complete되지 않는 경우가 있어(타임아웃 5s 발생),
     * future에는 의존하지 않고 콜백(`afterConnectionEstablished`)에서 세션을 받는다.
     * 실패는 `.whenComplete` 또는 콜백 미발화 시 `afterConnectionClosed`/외부 keepalive로 감지.
     */
    private fun connect() {
        if (!connecting.compareAndSet(false, true)) return
        try {
            log.info { "KIS WS 연결 시도 → ${properties.wsUrl}" }
            webSocketClient.execute(handler, properties.wsUrl)
                .whenComplete { _, err ->
                    if (err != null) {
                        log.warn(err) { "KIS WS 연결 실패, 백오프 재연결 예약" }
                        connecting.set(false)
                        scheduleReconnect()
                    }
                    // 성공은 afterConnectionEstablished에서 처리(connecting 해제 포함)
                }
        } catch (e: Exception) {
            log.warn(e) { "KIS WS execute 호출 자체 실패, 백오프 재연결 예약" }
            connecting.set(false)
            scheduleReconnect()
        }
    }

    private fun sendSubscription(session: WebSocketSession, sub: Subscription, subscribe: Boolean) {
        val payload = KisSubscribePayload(
            header = KisSubscribePayload.Header(
                approvalKey = approvalKeyProvider.approvalKey,
                custtype = "P",
                trType = if (subscribe) "1" else "2",
            ),
            body = KisSubscribePayload.Body(
                input = KisSubscribePayload.Body.Input(trId = sub.trId, trKey = sub.trKey),
            ),
        )
        log.info { "KIS WS ${if (subscribe) "구독" else "해지"} 전송 → trId=${sub.trId}, trKey=${sub.trKey}" }
        session.sendMessage(TextMessage(objectMapper.writeValueAsString(payload)))
    }

    /**
     * Spring `WebSocketHandler` 콜백을 inner object로 분리.
     * 직접 구현하면 `@Component` 빈이 `WebSocketHandler` 타입으로 노출되어
     * `@EnableWebSocketMessageBroker`의 STOMP 자동 설정과 충돌한다.
     */
    internal val handler: WebSocketHandler = object : WebSocketHandler {
        override fun afterConnectionEstablished(session: WebSocketSession) {
            log.info { "KIS WS 연결됨: sessionId=${session.id}" }
            currentSession.set(session)
            connecting.set(false)
            reconnectAttempt = 0
            _connectionState.tryEmit(true)
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

        override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
            log.warn(exception) { "KIS WS 전송 오류" }
        }

        override fun afterConnectionClosed(session: WebSocketSession, closeStatus: CloseStatus) {
            log.warn { "KIS WS 끊김 status=$closeStatus, 재연결 예약" }
            currentSession.compareAndSet(session, null)
            connecting.set(false)
            _connectionState.tryEmit(false)
            if (subscriptions.isNotEmpty()) scheduleReconnect()
        }

        override fun supportsPartialMessages(): Boolean = false
    }

    private fun handleJsonMessage(session: WebSocketSession, payload: String) {
        val node = objectMapper.readTree(payload)
        val header = node.path("header")
        val trId = header.path("tr_id").asText()
        if (trId == "PINGPONG") {
            session.sendMessage(TextMessage(payload))
            return
        }
        // SUBSCRIBE SUCCESS 응답에서 AES256 iv/key 추출 (체결통보 H0STCNI0 복호화용)
        val output = node.path("body").path("output")
        val key = output.path("key").asText("")
        val iv = output.path("iv").asText("")
        if (key.isNotBlank() && iv.isNotBlank()) {
            cipherKeys[trId] = AesKey(key.toByteArray(), iv.toByteArray())
            log.info { "KIS WS 복호화 키 등록: trId=$trId" }
        }
    }

    private fun handleRealtimeFrame(payload: String) {
        val parts = payload.split("|", limit = 4)
        if (parts.size < 4) return
        val encrypted = parts[0] == "1"
        val trId = parts[1]
        val rawBody = parts[3]
        val body = if (encrypted) {
            val cipher = cipherKeys[trId] ?: run {
                log.warn { "KIS WS 암호화 응답이지만 key 없음: trId=$trId" }
                return
            }
            decryptAes256(rawBody, cipher) ?: return
        } else {
            rawBody
        }
        when (trId) {
            TR_PRICE -> parsePriceTick(body)?.let { _priceTicks.tryEmit(it) }
            trExec -> parseExecutionNotice(body)?.let { _executionNotices.tryEmit(it) }
        }
    }

    private fun decryptAes256(base64Body: String, key: AesKey): String? {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key.key, "AES"),
                IvParameterSpec(key.iv),
            )
            val decoded = Base64.getDecoder().decode(base64Body)
            String(cipher.doFinal(decoded), Charsets.UTF_8)
        } catch (e: Exception) {
            log.warn(e) { "KIS WS 복호화 실패: trId payload prefix=${base64Body.take(20)}" }
            null
        }
    }

    private fun parsePriceTick(body: String): PriceTick? {
        val fields = body.split("^")
        if (fields.size < 3) return null
        val stockCode = fields[0].takeIf { it.isNotBlank() } ?: return null
        val price = fields[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return PriceTick(stockCode = stockCode, price = price, timestamp = Instant.now())
    }

    // H0STCNI0 응답 필드 매핑 (docs 순서, ^ 분리, 0-indexed):
    //  0:CUST_ID 1:ACNT_NO 2:ODER_NO 3:OODER_NO 4:SELN_BYOV_CLS 5:RCTF_CLS
    //  6:ODER_KIND 7:ODER_COND 8:STCK_SHRN_ISCD 9:CNTG_QTY 10:CNTG_UNPR
    //  11:STCK_CNTG_HOUR 12:RFUS_YN 13:CNTG_YN 14:ACPT_YN ...
    private fun parseExecutionNotice(body: String): ExecutionNotice? {
        val fields = body.split("^")
        if (fields.size < 14) return null
        // CNTG_YN: "1" 접수통보(주문/정정/취소/거부), "2" 체결통보 — 체결만 처리
        if (fields[13] != "2") return null
        // RFUS_YN: "1" 거부 — 무시
        if (fields[12] == "1") return null
        val sideCode = fields[4]
        val side = when (sideCode) {
            "02" -> OrderSide.BUY
            "01" -> OrderSide.SELL
            else -> return null
        }
        val orderNo = fields[2].takeIf { it.isNotBlank() } ?: return null
        val stockCode = fields[8].takeIf { it.isNotBlank() } ?: return null
        val qty = fields[9].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val price = fields[10].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return ExecutionNotice(
            orderNo = orderNo,
            stockCode = stockCode,
            side = side,
            executedQty = qty,
            executedPrice = price,
            timestamp = Instant.now(),
        )
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

    private data class Subscription(val trId: String, val trKey: String)

    private class AesKey(val key: ByteArray, val iv: ByteArray)

    companion object {
        private const val TR_PRICE = "H0UNCNT0"  // KRX+NXT 통합 시세 (NXT 애프터마켓 15:40~20:00 포함)
        private val BACKOFF_DELAYS_SEC = longArrayOf(1, 2, 5, 5)
    }
}
