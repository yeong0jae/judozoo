package at.backend.library.time

import java.time.LocalDate
import java.time.LocalDateTime

interface TimeProvider {
    fun now(): LocalDateTime
    fun today(): LocalDate = now().toLocalDate()
}
