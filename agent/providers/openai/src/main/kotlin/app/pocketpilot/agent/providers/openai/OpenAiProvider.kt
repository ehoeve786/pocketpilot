package app.pocketpilot.agent.providers.openai

import app.pocketpilot.agent.providers.api.ModelErrors
import app.pocketpilot.agent.providers.api.ModelEvent
import app.pocketpilot.agent.providers.api.ModelInfo
import app.pocketpilot.agent.providers.api.ModelProvider
import app.pocketpilot.agent.providers.api.ModelRequest
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

/**
 * The OpenAI Chat Completions API, and any server that speaks it at [baseUrl] (see
 * `openai-compatible`). Requests are not streamed: each [stream] call sends one request, waits for the
 * whole reply, then emits its events, so text arrives as a single [ModelEvent.TextDelta].
 *
 * Sends `max_tokens`, which every compatible server accepts; OpenAI's reasoning models want
 * `max_completion_tokens` instead and are not supported yet.
 */
class OpenAiProvider(
    private val http: HttpClient,
    private val apiKey: String?,
    baseUrl: String = DEFAULT_BASE_URL,
    override val id: String = "openai",
    override val displayName: String = "OpenAI",
    private val extraHeaders: Map<String, String> = emptyMap(),
) : ModelProvider {
    private val baseUrl = baseUrl.trimEnd('/')

    /**
     * Lists the models from `GET /models`. Throws [IOException] when the request fails, with the same
     * message an equivalent [ModelEvent.Error] would carry.
     */
    override suspend fun listModels(): List<ModelInfo> {
        val response = http.get("$baseUrl/models") { configure() }
        val body = response.boundedText()
        if (!response.status.isSuccess()) throw IOException(httpError(response, body).message)
        return runCatching { ChatCompletionsCodec.decodeModels(body) }.getOrElse { throw IOException("Unreadable model list", it) }
    }

    override fun stream(request: ModelRequest): Flow<ModelEvent> =
        flow {
            val events =
                try {
                    val response =
                        http.post("$baseUrl/chat/completions") {
                            configure()
                            setBody(TextContent(ChatCompletionsCodec.encodeRequest(request).toString(), ContentType.Application.Json))
                        }
                    val body = response.boundedText()
                    if (response.status.isSuccess()) {
                        ChatCompletionsCodec.decodeResponse(body) { "call_${toolCallIds.incrementAndGet()}" }
                    } else {
                        listOf(httpError(response, body))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    listOf(ModelErrors.network(e))
                }
            for (event in events) emit(event)
        }

    private fun HttpRequestBuilder.configure() {
        // Failures are reported as events, whatever the shared client's default is.
        expectSuccess = false
        if (!apiKey.isNullOrBlank()) header(HttpHeaders.Authorization, "Bearer $apiKey")
        for ((name, value) in extraHeaders) header(name, value)
    }

    private fun httpError(
        response: HttpResponse,
        body: String,
    ): ModelEvent.Error = ModelErrors.fromHttp(response.status.value, body, response.headers[HttpHeaders.RetryAfter])

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"

        /** Shared by every instance: key rotation and fallbacks make new providers mid-conversation. */
        private val toolCallIds = AtomicLong()
    }
}

/** The reply's body as text. A body over [MAX_BODY_BYTES] is refused, so a huge reply cannot fill memory. */
private suspend fun HttpResponse.boundedText(): String {
    val channel = bodyAsChannel()
    val out = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(CHUNK_BYTES)
    while (true) {
        val read = channel.readAvailable(chunk, 0, chunk.size)
        if (read < 0) break
        if (out.size() + read > MAX_BODY_BYTES) throw IOException("The provider's reply is larger than $MAX_BODY_BYTES bytes")
        out.write(chunk, 0, read)
    }
    return out.toString(Charsets.UTF_8)
}

private const val MAX_BODY_BYTES = 16 * 1024 * 1024
private const val CHUNK_BYTES = 8192
