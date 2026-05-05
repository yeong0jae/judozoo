package at.backend.trading.application

import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

@Component
class CommandGate {

    private val openFlag = AtomicBoolean(true)

    fun isOpen(): Boolean = openFlag.get()

    fun open() {
        openFlag.set(true)
    }

    fun close() {
        openFlag.set(false)
    }
}
