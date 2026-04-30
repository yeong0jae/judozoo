package at.backend.config

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest
class CoroutineConfigTest {

    @Autowired
    lateinit var applicationCoroutineScope: CoroutineScope

    @Test
    fun `applicationCoroutineScope 빈이 주입된다`() {
        assertNotNull(applicationCoroutineScope)
    }

    @Test
    fun `applicationCoroutineScope에서 launch가 동작한다`() = runBlocking {
        val counter = AtomicInteger(0)

        val job = applicationCoroutineScope.launch {
            delay(10)
            counter.incrementAndGet()
        }
        job.join()

        assertEquals(1, counter.get())
    }
}
