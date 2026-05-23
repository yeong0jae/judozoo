package at.backend.trading.application

import at.backend.trading.application.broker.BrokerTradingClient
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

@Component
class ExecutionNoticeListener(
    private val broker: BrokerTradingClient,
    private val handler: ExecutionNoticeHandler,
    private val applicationScope: CoroutineScope,
) {

    private val log = KotlinLogging.logger {}
    private var job: Job? = null

    @PostConstruct
    fun start() {
        broker.subscribeExecutionNotices()
        job = applicationScope.launch {
            broker.executionNotices.collect { notice ->
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
