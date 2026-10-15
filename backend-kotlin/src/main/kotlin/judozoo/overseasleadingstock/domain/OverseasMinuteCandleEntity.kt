package judozoo.overseasleadingstock.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 해외 종목 지난 거래일 1분봉. **완성된 날만** 들어온다 — 하루치를 통째로 갈아 끼운다.
 *
 * `trading_day`는 미국 현지 영업일, `minute_at`은 한국 시각이다. 미국 장은 한국 자정을 넘나들어
 * 한국 날짜로는 하루가 쪼개지므로 날은 현지 영업일로 가른다.
 *
 * 가격은 **단정밀도 `float` 컬럼**이다(Alembic 정의 그대로). 읽을 때 [exact]로 MySQL이 글자로
 * 주는 값(`230.17`)과 같게 만든다 — 그대로 넓히면 `230.1699981689453`이 되어 Python 응답과 어긋난다.
 */
@Entity
@Table(name = "overseas_minute_candle")
class OverseasMinuteCandleEntity(
    val exchange: String,
    val symbol: String,
    @Column(name = "trading_day")
    val tradingDay: LocalDate,
    @Column(name = "minute_at")
    val minuteAt: LocalDateTime,
    val open: Float,
    val high: Float,
    val low: Float,
    val close: Float,
    val volume: Long,
    @Column(name = "trading_value")
    val tradingValue: Float,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Int? = null

    /** 단정밀도로 저장된 값을 MySQL이 글자로 주는 값과 같게 넓힌다. */
    fun exact(value: Float): Double = value.toString().toDouble()
}
