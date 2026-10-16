package judozoo.platform.vertex

import io.micrometer.core.instrument.MeterRegistry
import judozoo.library.web.MeteredInterceptor
import judozoo.platform.vertex.VertexException.Kind
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpClient
import java.net.http.HttpTimeoutException
import java.time.Duration
import kotlin.random.Random

@Component
class VertexClient(
    private val properties: VertexProperties,
    private val token: VertexToken,
    private val registry: MeterRegistry,
    private val wait: (Duration) -> Unit = { Thread.sleep(it) },
    private val jitter: () -> Double = { Random.nextDouble(0.5, 1.5) },
    restClient: RestClient.Builder = RestClient.builder(),
) {
    private val json = JsonMapper.builder().build()

    data class Usage(val inputTokens: Long = 0, val outputTokens: Long = 0, val thoughtsTokens: Long = 0, val searchQueries: Int = 0)

    data class GroundedSource(val domain: String, val uri: String)

    data class Grounded(val text: String, val sources: List<GroundedSource> = emptyList(), val usage: Usage = Usage())

    private val rest = restClient.clone()
        .baseUrl(properties.baseUrl)
        .requestFactory(
            JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(properties.connectTimeout).build(),
            ).apply { setReadTimeout(properties.readTimeout) },
        )
        .requestInterceptor(MeteredInterceptor("vertex", registry) { ":generateContent" })
        .build()

    fun ground(prompt: String): Grounded {
        val body = mapOf(
            "contents" to listOf(mapOf("role" to "user", "parts" to listOf(mapOf("text" to prompt)))),
            "tools" to listOf(mapOf("googleSearch" to emptyMap<String, Any>())),
            "generationConfig" to mapOf("thinkingConfig" to mapOf("thinkingLevel" to "LOW")),
        )
        val data = generate(body, "ground")
        val candidate = data["candidates"][0]
        val meta = candidate["groundingMetadata"]
        val sources = meta?.get("groundingChunks")?.values().orEmpty().mapNotNull { chunk ->
            val web = chunk["web"] ?: return@mapNotNull null
            val uri = web.text("uri").ifEmpty { return@mapNotNull null }
            GroundedSource(domain = web.text("domain").ifEmpty { web.text("title") }, uri = uri)
        }
        val usage = usage(data, searchQueries = meta?.get("webSearchQueries")?.size() ?: 0)
        return Grounded(text(candidate), sources, usage)
    }

    fun structure(prompt: String, schema: Map<String, Any>): Pair<JsonNode, Usage> {
        val body = mapOf(
            "contents" to listOf(mapOf("role" to "user", "parts" to listOf(mapOf("text" to prompt)))),
            "generationConfig" to mapOf(
                "responseMimeType" to "application/json",
                "responseSchema" to schema,
                "thinkingConfig" to mapOf("thinkingLevel" to "LOW"),
            ),
        )
        val data = generate(body, "structure")
        val text = text(data["candidates"][0])
        val parsed = try {
            json.readTree(text)
        } catch (e: Exception) {
            throw VertexException(Kind.BAD_ANSWER, "structure JSON이 아닌 응답: ${text.take(200)}", e)
        }
        if (!parsed.isObject) throw VertexException(Kind.BAD_ANSWER, "structure 객체가 아닌 JSON: ${text.take(200)}")
        return parsed to usage(data)
    }

    private fun generate(body: Map<String, Any>, stage: String): JsonNode {
        val path = "/v1/projects/${properties.project}/locations/${properties.location}/publishers/google/models/${properties.model}:generateContent"
        val backoff = BACKOFF.iterator()
        var refresh = false
        while (true) {
            val (status, text) = post(path, body, refresh, stage)
            when {
                status == 401 && !refresh -> refresh = true
                status == 429 || status >= 500 -> {
                    if (!backoff.hasNext()) throw VertexException(Kind.UNAVAILABLE, "$stage HTTP $status: ${text.take(200)}")
                    val base = backoff.next()
                    wait(Duration.ofMillis((base.toMillis() * jitter()).toLong()))
                }
                status != 200 -> throw VertexException(Kind.CLIENT_ERROR, "$stage HTTP $status: ${text.take(200)}")
                else -> return usable(json.readTree(text), stage)
            }
        }
    }

    private fun post(path: String, body: Map<String, Any>, refresh: Boolean, stage: String): Pair<Int, String> = try {
        rest.post().uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer ${token.get(refresh)}")
            .body(body)
            .exchange { _, response -> response.statusCode.value() to response.body.readAllBytes().decodeToString() }
    } catch (e: ResourceAccessException) {
        val cause = generateSequence<Throwable>(e) { it.cause }.firstOrNull { it is HttpTimeoutException }
        if (cause != null) throw VertexException(Kind.TIMEOUT, "$stage 요청 실패: ${cause.javaClass.simpleName}", e)
        throw VertexException(Kind.UNAVAILABLE, "$stage 요청 실패: ${(e.cause ?: e).javaClass.simpleName}", e)
    }

    private fun usable(data: JsonNode, stage: String): JsonNode {
        val candidates = data["candidates"]?.values()?.toList().orEmpty()
        val finish = candidates.firstOrNull()?.get("finishReason")?.takeUnless { it.isNull }?.asString()
        if (candidates.isEmpty() || finish !in USABLE_FINISH) {
            val reason = if (candidates.isEmpty()) data["promptFeedback"] else finish
            throw VertexException(Kind.BAD_ANSWER, "$stage 쓸 수 있는 답이 없다: $reason")
        }
        return data
    }

    private fun text(candidate: JsonNode): String =
        candidate["content"]?.get("parts")?.values().orEmpty()
            .filterNot { it["thought"]?.asBoolean() == true }
            .joinToString("") { it.text("text") }
            .trim()

    private fun usage(data: JsonNode, searchQueries: Int = 0): Usage {
        val u = data["usageMetadata"]
        fun count(field: String) = u?.get(field)?.asLong() ?: 0L
        val usage = Usage(count("promptTokenCount"), count("candidatesTokenCount"), count("thoughtsTokenCount"), searchQueries)
        val model = properties.model
        registry.counter("llm_tokens", "model", model, "kind", "input").increment(usage.inputTokens.toDouble())
        registry.counter("llm_tokens", "model", model, "kind", "output").increment(usage.outputTokens.toDouble())
        registry.counter("llm_tokens", "model", model, "kind", "thoughts").increment(usage.thoughtsTokens.toDouble())
        registry.counter("llm_search_queries", "model", model).increment(usage.searchQueries.toDouble())
        return usage
    }

    companion object {
        private val BACKOFF = listOf(Duration.ofSeconds(2), Duration.ofSeconds(4))

        private val USABLE_FINISH = setOf(null, "STOP", "MAX_TOKENS")

        private fun JsonNode.text(field: String) = get(field)?.takeUnless { it.isNull }?.asString().orEmpty()
    }
}
