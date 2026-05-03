package at.backend.system.presentation

import at.backend.library.web.ApiResponse
import at.backend.system.application.SystemService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class SystemController(private val systemService: SystemService) {

    @GetMapping("/api/system/status")
    fun getStatus() = ApiResponse.ok(systemService.getStatus())
}
