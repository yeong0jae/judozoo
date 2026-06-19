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
    val fluRt: Double, // 당일 등락률(%)
)
