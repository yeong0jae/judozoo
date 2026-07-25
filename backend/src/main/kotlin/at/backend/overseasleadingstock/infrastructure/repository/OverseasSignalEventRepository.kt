package at.backend.overseasleadingstock.infrastructure.repository

import at.backend.overseasleadingstock.domain.OverseasSignalEvent
import org.springframework.data.jpa.repository.JpaRepository

interface OverseasSignalEventRepository : JpaRepository<OverseasSignalEvent, Long>
