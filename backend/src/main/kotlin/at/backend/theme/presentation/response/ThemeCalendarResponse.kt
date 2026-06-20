package at.backend.theme.presentation.response

import java.time.LocalDate

data class ThemeCalendarResponse(
    val days: List<ThemeDayItem>,
)

data class ThemeDayItem(
    val date: LocalDate,
    val themes: List<ThemeItem>,
)

data class ThemeItem(
    val rank: Int,
    val name: String,
    val tradingValue: Long, // 테마 소속 상위 종목 거래대금 합산(원)
)
