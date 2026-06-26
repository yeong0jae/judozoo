package at.backend.leadingstock.application

import at.backend.leadingstock.domain.IndexMinuteCandle
import at.backend.leadingstock.domain.IndexMinuteCandleEntity
import at.backend.leadingstock.infrastructure.repository.IndexMinuteCandleRepository
import at.backend.stock.domain.Market
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/** 지수 1분봉 영속화/조회. 폴러가 upsert하고, 차트가 일자별로 읽는다. */
@Service
class IndexMinuteCandleService(
    private val repository: IndexMinuteCandleRepository,
) {

    /** (시장, 분) 기준 upsert — 진행 중인 분은 더 완성된 값으로 갱신. */
    @Transactional
    fun upsertAll(market: Market, candles: List<IndexMinuteCandle>) {
        candles.forEach { c ->
            val existing = repository.findByMarketAndMinute(market, c.minute)
            if (existing != null) {
                existing.updateFrom(c)
            } else {
                repository.save(
                    IndexMinuteCandleEntity(
                        market = market,
                        tradeDate = c.minute.toLocalDate(),
                        minute = c.minute,
                        open = c.open,
                        high = c.high,
                        low = c.low,
                        close = c.close,
                        volume = c.volume,
                    ),
                )
            }
        }
    }

    @Transactional(readOnly = true)
    fun candlesOn(market: Market, date: LocalDate): List<IndexMinuteCandle> =
        repository.findByMarketAndTradeDateOrderByMinuteAsc(market, date).map { it.toDomain() }
}
