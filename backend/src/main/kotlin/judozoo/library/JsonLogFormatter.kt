package judozoo.library

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import org.springframework.boot.json.JsonWriter
import org.springframework.boot.logging.structured.StructuredLogFormatter
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 한 줄 = JSON 객체 하나. Python `JsonFormatter`와 **같은 필드**다 — Grafana 알림이
 * `| json | level = "ERROR"`로 걸려 있어 이름이 다르면 오류가 조용히 빠진다.
 *
 * - `ts`는 오프셋을 붙인 ISO8601(`+09:00`). 없으면 Loki가 UTC로 읽어 9시간이 어긋난다
 * - `level`은 Python 이름을 쓴다 — `WARN`이 아니라 `WARNING`
 * - `trace_id`·`user`·`request`는 **없는 게 정상인 로그가 많다**(기동·스케줄러·비로그인)
 */
class JsonLogFormatter(private val zone: ZoneId = ZoneId.systemDefault()) : StructuredLogFormatter<ILoggingEvent> {
    private val json = JsonWriter.standard<Map<String, Any>>().withNewLineAtEnd()

    override fun format(event: ILoggingEvent): String {
        val payload = linkedMapOf<String, Any>(
            "ts" to TS.format(event.instant.atZone(zone)),
            "level" to if (event.level == Level.WARN) "WARNING" else event.level.toString(),
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
        /** Micrometer Tracing이 MDC에 심는 키. */
        const val MDC_TRACE_ID = "traceId"
        const val MDC_USER = "user"
        const val MDC_REQUEST = "request"

        private val TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    }
}
