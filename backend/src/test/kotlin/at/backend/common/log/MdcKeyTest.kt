package at.backend.common.log

import at.backend.library.log.MdcKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.slf4j.MDC

class MdcKeyTest {

    @Test
    fun `MDC 값이 코루틴 경계를 넘어 보존된다`() = runBlocking {
        MDC.put(MdcKey.CYCLE_ID, "cmd-123")

        withContext(Dispatchers.IO + MDCContext()) {
            assertEquals("cmd-123", MDC.get(MdcKey.CYCLE_ID))
        }

        MDC.clear()
    }
}
