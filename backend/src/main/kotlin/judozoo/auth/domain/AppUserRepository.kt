package judozoo.auth.domain

import org.springframework.data.jpa.repository.JpaRepository

interface AppUserRepository : JpaRepository<AppUser, Long> {
    fun findByGoogleSub(googleSub: String): AppUser?
}
