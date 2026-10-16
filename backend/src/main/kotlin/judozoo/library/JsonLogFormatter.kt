package judozoo.library

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import org.springframework.boot.json.JsonWriter
import org.springframework.boot.logging.structured.StructuredLogFormatter
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class JsonLogFormatter(private val zone: ZoneId = ZoneId.systemDefault()) : StructuredLogFormatter<ILoggingEvent> {
    private val json = JsonWriter.standard<Map<String, Any>>().withNewLineAtEnd()

    override fun format(event: ILoggingEvent): String {
        val payload = linkedMapOf<String, Any>(
            "ts" to TS.format(event.instant.atZone(zone)),
            "level" to event.level.toString(),
            "logger" to event.loggerName,
            "msg" to event.formattedMessage,
            "thread" to event.threadName,
        )
        event.mdcPropertyMap[MDC_TRACE_ID]?.let { payload["trace_id"] = it }
        event.mdcPropertyMap[MDC_USER]?.let { payload["user"] = it.toLongOrNull() ?: it }
        event.mdcPropertyMap[MDC_REQUEST]?.let { payload["request"] = it }
        event.throwableProxy?.let { payload["exc"] = ThrowableProxyUtil.asString(it) }
        return json.writeToString(payload)
    }

    companion object {
        const val MDC_TRACE_ID = "traceId"
        const val MDC_USER = "user"
        const val MDC_REQUEST = "request"

        private val TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    }
}
