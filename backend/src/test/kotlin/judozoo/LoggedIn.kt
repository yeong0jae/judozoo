package judozoo

import jakarta.servlet.http.Cookie
import judozoo.auth.presentation.LoginGateFilter
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

object LoggedIn {
    const val SECRET = "judozoo.session-secret=test-secret"

    val cookie = Cookie(
        LoginGateFilter.COOKIE_NAME,
        "eyJ1c2VyIjogeyJpZCI6IDcsICJlbWFpbCI6ICJhQGV4YW1wbGUuY29tIn0sICJfc3RhdGVfZ29vZ2xlX3giOiB7ImRhdGEiOiAxfX0=" +
            ".atDSsQ._chmEutnIautJuu1n_0keYIpTb0",
    )

    @TestConfiguration
    class ClockAtSigning {
        @Bean
        fun clock(): Clock = Clock.fixed(Instant.ofEpochSecond(1_792_070_321), ZoneOffset.UTC)
    }
}
