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

// --- 분봉 캔들차트 API ---

/** 1분봉 한 개. time은 체결시각(ISO LocalDateTime, KST 벽시계). tradingValue=거래대금(종가×거래량 근사). */
data class MinuteCandleItem(
    val time: LocalDateTime,
    val open: Long,
    val high: Long,
    val low: Long,
    val close: Long,
    val volume: Long,
    val tradingValue: Long,
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
    val eventType: String,       // BREAKOUT | BREAKOUT_IMMINENT | VOLUME_SPIKE | MA20_CROSS
    val currentPrice: Long,
    val priceChangeRate: Double, // 발생 시점 당일 등락률(%)
    val tradingValue: Long,      // 발생 시점 당일 누적 거래대금(원)
    val gapRate: Double?,        // 돌파 계열만
    val spikeRatio: Double?,     // 스파이크만
    val minuteTradingValue: Long?, // 스파이크만 — 발생 분봉 거래대금(원)
    val spikeDirection: String?, // 스파이크만 — BUY | SELL | FLAT
    val ma20: Long?,             // 돌림만 — 그 시점 5분봉 20이평값(원)
    val theme: String?,          // 대표 테마
)

// --- 시장(코스피/코스닥) 투자자 순매수 시그널 API ---

data class MarketSignalEventsResponse(
    val date: java.time.LocalDate,
    val totalCount: Int,
    val events: List<MarketSignalEventItem>,
)

/**
 * [kind]=NET_BUY_LEVEL이면 investor/level/thresholdEok/netAmountEok(억원)이 채워진다.
 * [kind]=NET_FLOW_TURN이면 investor/extremeAmountEok(정점)/netAmountEok(전환 시점)이 채워진다.
 * [thresholdEok]=도달 단계의 기준선(level×단계크기).
 */
data class MarketSignalEventItem(
    val occurredAt: LocalDateTime,
    val kind: String,      // NET_BUY_LEVEL | NET_FLOW_TURN | MA20_REBOUND | MA20_BREAKDOWN
    val market: String,    // KOSPI | KOSDAQ
    val side: String,      // BUY | SELL
    val investor: String?, // 순매수: FOREIGN | INSTITUTION | INDIVIDUAL
    val level: Int?,       // 순매수: 도달 단계 (1=1단계)
    val thresholdEok: Long?, // 순매수: 단계 기준선(억원)
    val netAmountEok: Long?, // 순매수/전환: 발생 시점 누적 순매수(억원, 부호 포함)
    val extremeAmountEok: Long?, // 전환: 되돌리기 직전 정점 누적(억원, 부호 포함)
    val indexValue: Double?, // 발생 시점 지수값
    val changeRate: Double?, // 발생 시점 등락률(%)
)

/** 시장(코스피/코스닥) 장 마감(15:40) 투자자 순매수 스냅샷 — 타임라인용. 순매수 단위는 억원(부호 포함). */
data class MarketCloseSnapshotItem(
    val capturedAt: LocalDateTime,
    val market: String,        // KOSPI | KOSDAQ
    val foreignEok: Long,      // 외국인
    val institutionEok: Long,  // 기관
    val individualEok: Long,   // 개인
    val indexValue: Double?,
    val changeRate: Double?,   // %
)

/** 시장(코스피/코스닥) 당일 누적 투자자 순매수 — 상세 패널용. 순매수 단위는 억원(부호 포함). */
data class MarketInvestorNetBuyItem(
    val market: String,        // KOSPI | KOSDAQ
    val foreignEok: Long,      // 외국인
    val institutionEok: Long,  // 기관
    val individualEok: Long,   // 개인
    val indexValue: Double,
    val changeRate: Double,    // %
)

/** 지수 1분봉 차트용 — 가격은 지수값(소수). volume은 1000주 단위. */
data class IndexMinuteCandleItem(
    val time: LocalDateTime,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
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

/** 일봉 한 개 — 일봉 차트용. date는 yyyy-MM-dd. */
data class DailyCandleChartItem(
    val date: String,
    val open: Long,
    val high: Long,
    val low: Long,
    val close: Long,
    val volume: Long,
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
