package at.backend.leadingstock.presentation.response

import java.time.LocalDateTime

// --- 후보 리스트 API ---

data class CandidateStocksResponse(
    val queriedAt: LocalDateTime,
    val totalCount: Int,
    val stocks: List<CandidateStockItem>,
)

data class CandidateStockItem(
    val rank: Int,
    val stockCode: String,
    val stockName: String,
    val currentPrice: Long,
    val priceChangeRate: Double,
    val accumulatedTradingValue: Long,
    val themes: List<String>,   // 대표 테마명(상위 N개)
    val themeCount: Int,        // 종목이 속한 전체 테마 수 ("+N" 표기용)
)

// --- 돌파 임박 레이더 API ---

data class BreakoutRadarResponse(
    val queriedAt: LocalDateTime,
    val totalCount: Int,
    val stocks: List<BreakoutRadarItem>,
)

data class BreakoutRadarItem(
    val stockCode: String,
    val stockName: String,
    val currentPrice: Long,
    val priceChangeRate: Double, // 당일 등락률(%)
    val dayHigh: Long, // 돌파선(당일 고가)
    val peakAt: LocalDateTime, // 돌파선 형성 분봉 시각
    val gapRate: Double, // 돌파까지 남은 상승률(%)
    val tradingValue: Long, // 당일 누적 거래대금(원)
    val themes: List<String>,
    val themeCount: Int,
)

// --- 분봉 거래대금 스파이크 API ---

data class VolumeSpikeResponse(
    val queriedAt: LocalDateTime,
    val totalCount: Int,
    val stocks: List<VolumeSpikeItem>,
)

data class VolumeSpikeItem(
    val stockCode: String,
    val stockName: String,
    val currentPrice: Long,
    val priceChangeRate: Double,
    val minuteTradingValue: Long, // 최신 1분봉 거래대금(원)
    val spikeRatio: Double,       // 직전 평균 대비 배율
    val at: LocalDateTime,        // 해당 분봉 시각
)

// --- 시그널 전이 로그 API ---

data class SignalEventsResponse(
    val date: java.time.LocalDate,
    val totalCount: Int,
    val events: List<SignalEventItem>,
)

data class SignalEventItem(
    val occurredAt: LocalDateTime,
    val stockCode: String,
    val stockName: String,
    val eventType: String,       // BREAKOUT | BREAKOUT_IMMINENT | VOLUME_SPIKE
    val currentPrice: Long,
    val priceChangeRate: Double, // 발생 시점 당일 등락률(%)
    val tradingValue: Long,      // 발생 시점 당일 누적 거래대금(원)
    val gapRate: Double?,        // 돌파 계열만
    val spikeRatio: Double?,     // 스파이크만
    val theme: String?,          // 대표 테마
)

// --- 종목 상세 API ---

data class LeadingStockDetailResponse(
    val stockCode: String,
    val stockName: String,
    val currentPrice: Long,
    val priceChangeRate: Double,
    val relativeVolume: Double?, // 풀데이 RVOL — 당일 누적/직전 20일 평균 거래량. 데이터 없으면 null
    val swingHighSignal: SwingHighSignalItem?, // 직전 스윙 고점 돌파 시그널. 데이터 없으면 null
    val themes: List<String>, // 종목이 속한 전체 테마명
    val filterResults: List<FilterResultItem>,
)

/** 직전 스윙 고점 돌파 매매 시그널. gapRate 양수=남은 상승률, 음수=이미 돌파. */
data class SwingHighSignalItem(
    val peakPrice: Long,
    val peakAt: LocalDateTime, // 전고점이 형성된 분봉 시각
    val gapRate: Double,
)

data class FilterResultItem(
    val filterName: String,
    val criteriaDescription: String,
    val actualValue: String,
    val passed: Boolean,
)

// --- 투자자 추이 API ---

/** 단위: 백만원. 양수=순매수, 음수=순매도. NXT 컬럼은 NXT 거래소 단독 값. */
data class InvestorTrendDayItem(
    val date: String,         // yyyy-MM-dd
    val individualNet: Long,
    val foreignNet: Long,
    val institutionNet: Long,
    val individualNetNxt: Long,
    val foreignNetNxt: Long,
    val institutionNetNxt: Long,
)
