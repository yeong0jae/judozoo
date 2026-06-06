package at.backend.market.application

import at.backend.library.time.TimeProvider
import at.backend.market.domain.event.RegimeUpdated
import at.backend.trading.application.broker.BrokerTradingClient
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

/**
 * 시장 레짐을 주기적으로 폴링해 산출·발행한다.
 *
 * 08:00~08:05는 NXT 거래대금 순위가 튀어 노이즈가 크므로 [openAt](08:05)부터 수집한다.
 * [anchorAt](08:15)에 아침 NXT 바스켓을 고정하고, [closeAt](본장 마감)까지 Gap2를 갱신한다.
 * 테스트 컨텍스트에서는 실제 네트워크를 때리지 않도록 제외한다.
 */
@Component
@Profile("!test")
class RegimePoller(
    private val service: MarketRegimeService,
    private val broker: BrokerTradingClient,
    private val timeProvider: TimeProvider,
    private val applicationScope: CoroutineScope,
    private val eventPublisher: ApplicationEventPublisher,
    @Value("\${trading.market.regime.poll-interval-millis}") private val pollIntervalMillis: Long,
    @Value("\${trading.market.regime.start-time}") openTime: String,
    @Value("\${trading.market.regime.anchor-time}") anchorTime: String,
    @Value("\${trading.market.regime.end-time}") closeTime: String,
    @Value("\${trading.market.regime.basket-size}") private val basketSize: Int,
    @Value("\${trading.market.regime.fetch-count}") private val fetchCount: Int,
) {
    private val log = KotlinLogging.logger {}
    private val openAt = LocalTime.parse(openTime)
    private val anchorAt = LocalTime.parse(anchorTime)
    private val closeAt = LocalTime.parse(closeTime)
    private val pollingJob = AtomicReference<Job?>(null)

    @PostConstruct
    fun start() {
        val job = applicationScope.launch {
            while (isActive) {
                runCatching { pollOnce() }.onFailure { log.warn(it) { "레짐 폴링 실패" } }
                delay(pollIntervalMillis.milliseconds)
            }
        }
        pollingJob.set(job)
    }

    @PreDestroy
    fun stop() {
        val job = pollingJob.getAndSet(null) ?: return
        runBlocking { job.cancelAndJoin() }
    }

    private fun pollOnce() {
        val now = timeProvider.now().toLocalTime()
        if (now < openAt || now > closeAt) return                 // 집계 시간 밖
        if (!broker.isMarketOpen(timeProvider.today())) return     // 휴장일

        val basket = service.fetchBasket(basketSize, fetchCount)
        if (now >= anchorAt) service.captureAnchor(basket)         // 08:15 도달 시 앵커 고정
        val snapshot = service.refresh(basket)
        eventPublisher.publishEvent(RegimeUpdated(snapshot))
    }
}
