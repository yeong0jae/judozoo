package at.backend.leadingstock.application

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 주도주 후보 필터 임계값. 키움 API의 단위/규모 그대로 반영(억원, 백만원 등).
 * 변경 시 application.yaml `leading-stock.criteria.*` 수정.
 */
@ConfigurationProperties(prefix = "leading-stock.criteria")
data class LeadingStockCriteriaProperties(
    val minMarketCap: Long = 3000,                  // 시가총액 최소(억원)
    val maxTradingValueRank: Int = 30,              // 거래대금 상위 N위 이내
    val minDailyPriceChangeRate: Double = 5.0,      // 당일 등락률(%)
    val maxHighPositionDropRate: Double = -5.0,     // 60봉 고가 대비(%)
    val minMinuteVolumeIncreaseRate: Double = 500.0,// 1분봉 거래대금 증가율(%)
    val minMinuteTradingValue: Long = 5_000_000_000,// 1분봉 거래대금(원)
    val maxMinuteFluctuationRate: Double = 4.0,     // 1분봉 등락률(%)
    val minProgramNetBuy: Long = -10_000,           // 프로그램 순매수 최소(백만원)
    val maxPrevCloseChangeRate: Double = 25.0,      // 전일 종가 대비(%)
    val maxOpeningPriceChangeRate: Double = 7.0,    // 시초가 대비(%)
    val maxThemeRank: Int = 5,                      // 테마 그룹 상위 N위
    val swingHighPivotWindow: Int = 5,              // 스윙 고점 프랙탈 피벗 좌우 봉 수
)
