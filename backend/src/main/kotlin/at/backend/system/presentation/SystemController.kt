package at.backend.system.presentation

import at.backend.library.web.ApiResponse
import at.backend.system.application.InstanceInfoService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class SystemController(
    private val instanceInfoService: InstanceInfoService,
) {

    @GetMapping("/api/system/instance")
    fun getInstance() = ApiResponse.ok(instanceInfoService.get())
}
