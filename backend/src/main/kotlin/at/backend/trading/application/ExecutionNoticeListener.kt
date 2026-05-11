package at.backend.trading.application

import at.backend.platform.kis.client.KisWebSocketClient
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

@Component
class ExecutionNoticeListener(
    private val webSocketClient: KisWebSocketClient,
    private val handler: ExecutionNoticeHandler,
    private val applicationScope: CoroutineScope,
) {

    private val log = KotlinLogging.logger {}
    private var job: Job? = null

    @PostConstruct
    fun start() {
        webSocketClient.subscribeExecutionNotice()
        job = applicationScope.launch {
            webSocketClient.executionNotices.collect { notice ->
                runCatching { handler.handle(notice) }
                    .onFailure { log.error(it) { "체결 통보 처리 실패 orderNo=${notice.orderNo}" } }
            }
        }
    }

    @PreDestroy
    fun stop() {
        job?.cancel()
    }
}
