package at.backend.platform.kis

import at.backend.platform.kis.client.KisAuthClient
import org.springframework.stereotype.Component

@Component
class KisApprovalKeyProvider(private val authClient: KisAuthClient) {

    val approvalKey: String by lazy { authClient.issueApprovalKey().approvalKey }
}
