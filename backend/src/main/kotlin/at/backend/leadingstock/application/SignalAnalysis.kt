package at.backend.leadingstock.application

import at.backend.leadingstock.domain.SignalKind
import at.backend.leadingstock.domain.TimeBucket
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 시그널 분석 한 날짜분 — 종목 묶음(성과순) + 그날 집계 + 전체 누적 통계.
 * 하루치 집계는 표본이 작아 "통계"가 아니라 "그날 성격"으로, 전체 통계가 "본인 승률 사전"에 가깝다.
 */
data class SignalAnalysis(
    val date: LocalDate,
    val dayStats: List<SignalKindStat>,
    val overallStats: List<SignalKindStat>,
    val stocks: List<StockSignalGroup>,
)

/** 사후 수익률 평균·승률 묶음. 평균은 라벨이 채워진 건만 대상, 측정 불가는 null. */
data class SignalMetrics(
    val avg1m: Double?,
    val avg2m: Double?,
    val avg20m: Double?,
    val avg2h: Double?,
    val avgClose: Double?,
    val avgMfe: Double?,
    val avgMae: Double?,
    /** +20분 수익률 > 0 비율(%). 라벨된 건 기준. */
    val winRate20m: Double?,
)

/** 종류(매수/매도 스파이크 분리)별 집계 + 시간대별 하위 집계([byBucket]). */
data class SignalKindStat(
    val kind: SignalKind,
    val count: Int,
    val labeled: Int,
    val metrics: SignalMetrics,
    val byBucket: List<TimeBucketStat>,
)

/** 한 종류 안에서 시간대별로 더 쪼갠 집계. */
data class TimeBucketStat(
    val bucket: TimeBucket,
    val count: Int,
    val labeled: Int,
    val metrics: SignalMetrics,
)

/** 한 종목의 그날 여정 — 신호들을 시간순으로 묶고, 헤더에 종목 단위 성과를 요약. */
data class StockSignalGroup(
    val stockCode: String,
    val stockName: String,
    val theme: String?,
    /** 그날 이 종목 신호들 중 최대 고점 수익률(정렬 기준). */
    val bestMfe: Double?,
    /** 첫 신호 기준 당일 종가 수익률. */
    val closeRet: Double?,
    val signals: List<SignalRow>,
)

/** 신호 1건 + 그 사후 라벨. 라벨이 아직 없으면 수익률들은 null. */
data class SignalRow(
    val occurredAt: LocalDateTime,
    val kind: SignalKind,
    val currentPrice: Long,
    val priceChangeRate: Double,
    val gapRate: Double?,
    val spikeRatio: Double?,
    val ret1m: Double?,
    val ret2m: Double?,
    val ret20m: Double?,
    val ret2h: Double?,
    val retClose: Double?,
    val mfe: Double?,
    val mae: Double?,
)
