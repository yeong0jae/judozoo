package at.backend.leadingstock.domain

/**
 * 시그널 1건의 사후 결과 — 진입가(신호 순간 현재가) 대비 수익률(%)들.
 * 측정 구간이 분봉을 벗어나면(예: 장 막판 신호의 +30분) 해당 값은 null = 측정 불가.
 *
 * - [ret5m]/[ret10m]/[ret30m]: 신호 후 5·10·30분 시점 종가 수익률
 * - [retClose]: 당일 종가 수익률
 * - [mfe]: 신호 후 당일 최대 상승(고점까지) / [mae]: 신호 후 당일 최대 하락(저점까지)
 */
data class SignalLabelResult(
    val ret5m: Double?,
    val ret10m: Double?,
    val ret30m: Double?,
    val retClose: Double?,
    val mfe: Double?,
    val mae: Double?,
)
