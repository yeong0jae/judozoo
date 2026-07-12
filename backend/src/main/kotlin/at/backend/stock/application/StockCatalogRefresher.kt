package at.backend.stock.application

import at.backend.library.time.TimeProvider
import at.backend.stock.infrastructure.KisOverseasStockMasterClient
import at.backend.stock.infrastructure.KisStockMasterClient
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 종목 카탈로그를 메모리에 적재한다.
 *
 * - 부팅 시: 오늘 이미 동기화됐으면 DB에서 그대로 적재(다운로드 생략),
 *   아니면 마스터 파일을 받아 전체 교체 후 적재.
 * - 매일 개장 전 1회 동일 루틴으로 갱신.
 * - 다운로드 실패는 fail-soft: 부팅이 막히지 않고, DB에 남은 직전 데이터라도 적재한다.
 */
@Component
class StockCatalogRefresher(
    private val client: KisStockMasterClient,
    private val store: StockCatalogStore,
    private val catalog: StockCatalog,
    private val overseasClient: KisOverseasStockMasterClient,
    private val overseasStore: OverseasStockCatalogStore,
    private val overseasCatalog: OverseasStockCatalog,
    private val timeProvider: TimeProvider,
) {

    private val log = KotlinLogging.logger {}

    @EventListener(ApplicationReadyEvent::class)
    fun onStartup() = refresh()

    @Scheduled(cron = "0 30 8 * * *", zone = "Asia/Seoul")
    fun onSchedule() = refresh()

    fun refresh() {
        refreshDomestic()
        refreshOverseas()
    }

    private fun refreshDomestic() {
        runCatching {
            if (store.lastSyncedDate() == timeProvider.today()) {
                val cached = store.loadAll()
                catalog.replace(cached)
                log.info { "종목 카탈로그 — 오늘 이미 동기화됨, DB에서 ${cached.size}건 적재" }
            } else {
                val fetched = client.fetchAll()
                store.replaceAll(fetched)
                catalog.replace(fetched)
                log.info { "종목 카탈로그 갱신 완료 — ${fetched.size}건" }
            }
        }.onFailure { e ->
            log.warn(e) { "종목 카탈로그 갱신 실패 — DB의 직전 데이터로 폴백" }
            runCatching { catalog.replace(store.loadAll()) }
        }
    }

    private fun refreshOverseas() {
        runCatching {
            if (overseasStore.lastSyncedDate() == timeProvider.today()) {
                val cached = overseasStore.loadAll()
                overseasCatalog.replace(cached)
                log.info { "해외 종목 카탈로그 — 오늘 이미 동기화됨, DB에서 ${cached.size}건 적재" }
            } else {
                val fetched = overseasClient.fetchAll()
                overseasStore.replaceAll(fetched)
                overseasCatalog.replace(fetched)
                log.info { "해외 종목 카탈로그 갱신 완료 — ${fetched.size}건" }
            }
        }.onFailure { e ->
            log.warn(e) { "해외 종목 카탈로그 갱신 실패 — DB의 직전 데이터로 폴백" }
            runCatching { overseasCatalog.replace(overseasStore.loadAll()) }
        }
    }
}
