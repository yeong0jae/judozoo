package at.backend.system.application

import org.springframework.core.env.Environment
import org.springframework.stereotype.Service

/**
 * 현재 인스턴스의 broker × env 식별 정보. 활성 Spring profile에서 직접 추출.
 * UI 헤더에 어느 인스턴스를 보고 있는지 표시하기 위한 용도.
 */
@Service
class InstanceInfoService(
    private val environment: Environment,
) {

    fun get(): InstanceInfo {
        val active = environment.activeProfiles.toSet()
        val broker = BROKER_PROFILES.firstOrNull { it in active } ?: "unknown"
        val env = ENV_PROFILES.firstOrNull { it in active } ?: "unknown"
        return InstanceInfo(broker = broker, env = env, label = label(broker, env))
    }

    private fun label(broker: String, env: String): String = when (broker to env) {
        "kis" to "vts" -> "KIS 모의"
        "kis" to "real" -> "KIS 실전"
        "kiwoom" to "vts" -> "Kiwoom 모의"
        "kiwoom" to "real" -> "Kiwoom 실전"
        else -> "$broker / $env"
    }

    data class InstanceInfo(val broker: String, val env: String, val label: String)

    companion object {
        private val BROKER_PROFILES = listOf("kis", "kiwoom")
        private val ENV_PROFILES = listOf("vts", "real")
    }
}
