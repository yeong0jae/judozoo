package at.backend.trading.application

import at.backend.platform.kis.client.KisWebSocketClient
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class ExecutionNoticeListener(
    private val webSocketClient: KisWebSocketClient,
    private val handler: ExecutionNoticeHandler,
    private val applicationScope: CoroutineScope,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private var job: Job? = null

    @PostConstruct
    fun start() {
        webSocketClient.subscribeExecutionNotice()
        job = applicationScope.launch {
            webSocketClient.executionNotices.collect { notice ->
                runCatching { handler.handle(notice) }
                    .onFailure { log.error("체결 통보 처리 실패 orderNo={}", notice.orderNo, it) }
            }
        }
    }

    @PreDestroy
    fun stop() {
        job?.cancel()
    }
}
