package at.backend.leadingstock.domain

/**
 * 시그널 1건의 사후 결과 — 진입가(신호 순간 현재가) 대비 수익률(%)들.
 * 측정 구간이 분봉을 벗어나면(예: 장 막판 신호의 +30분) 해당 값은 null = 측정 불가.
 *
 * - [ret1m]/[ret2m]/[ret20m]/[ret2h]: 신호 후 1·2·20분·2시간 시점 종가 수익률
 * - [retClose]: 당일 종가 수익률
 * - [mfe]: 신호 후 당일 최대 상승(고점까지) / [mae]: 신호 후 당일 최대 하락(저점까지)
 */
data class SignalLabelResult(
    val ret1m: Double?,
    val ret2m: Double?,
    val ret20m: Double?,
    val ret2h: Double?,
    val retClose: Double?,
    val mfe: Double?,
    val mae: Double?,
)
