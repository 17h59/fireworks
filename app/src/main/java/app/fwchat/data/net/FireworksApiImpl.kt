package app.fwchat.data.net

import app.fwchat.domain.ChatRequest
import app.fwchat.domain.FireworksApi
import app.fwchat.domain.FireworksException
import app.fwchat.domain.ModelInfo
import app.fwchat.domain.StreamEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

/**
 * Client Fireworks (OkHttp). Aucune dépendance Android.
 *
 * @param baseUrl racine sans slash final (injectable pour MockWebServer).
 * @param streamIdleTimeoutSeconds délai max sans aucun octet reçu pendant le streaming (readTimeout du client SSE).
 * @param maxAttempts nombre maximal d'essais d'une requête de chat (1 = pas de relance). Une relance n'a lieu que sur
 * 429 ou 5xx survenus AVANT le premier événement du flux; jamais après un delta reçu.
 * @param retryBaseDelayMs délai de base du backoff exponentiel (avec jitter); `Retry-After` est respecté s'il est présent.
 * @param maxRetryAfterMs au-delà de cette attente demandée par le serveur, on ne relance pas et l'erreur est remontée.
 */
class FireworksApiImpl(
    client: OkHttpClient,
    baseUrl: String = DEFAULT_BASE_URL,
    streamIdleTimeoutSeconds: Long = 120,
    private val maxAttempts: Int = 3,
    private val retryBaseDelayMs: Long = 1000,
    private val maxRetryAfterMs: Long = 20_000,
    private val random: Random = Random.Default,
) : FireworksApi {

    private val base = baseUrl.trimEnd('/')
    private val httpClient: OkHttpClient = client
    private val streamClient: OkHttpClient = client.newBuilder()
        .readTimeout(streamIdleTimeoutSeconds, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    // ---------------------------------------------------------------- listModels

    override suspend fun listModels(apiKey: String): List<ModelInfo> {
        val out = LinkedHashMap<String, ModelInfo>()
        var token: String? = null
        // Le filtre serveur réduit la réponse de ~1 Mo à ~80 Ko. S'il est refusé (400), on retente sans.
        var useFilter = true
        val seenTokens = HashSet<String>()
        while (true) {
            val url = "$base/v1/accounts/fireworks/models".toHttpUrl().newBuilder()
                .addQueryParameter("pageSize", "200")
                .apply {
                    if (useFilter) addQueryParameter("filter", "supports_serverless=true")
                    if (token != null) addQueryParameter("pageToken", token)
                }
                .build()
            val body = try {
                get(url.toString(), apiKey)
            } catch (e: FireworksException.Http) {
                if (useFilter && e.code == 400) {
                    useFilter = false
                    token = null
                    out.clear()
                    seenTokens.clear()
                    continue
                }
                throw e
            }
            val root = parseObject(body)
            (root["models"] as? JsonArray)?.forEach { el ->
                (el as? JsonObject)?.let(::parseModel)?.let { out[it.id] = it }
            }
            val next = root.str("nextPageToken")?.takeIf { it.isNotEmpty() }
            if (next == null || !seenTokens.add(next)) break
            token = next
        }
        return out.values.sortedWith(compareBy({ it.displayName.lowercase() }, { it.id }))
    }

    private fun parseObject(body: String): JsonObject = try {
        LenientJson.parseToJsonElement(body) as? JsonObject ?: throw IllegalArgumentException("not an object")
    } catch (e: Exception) {
        throw FireworksException.Network(IOException("Réponse illisible du serveur", e))
    }

    private fun parseModel(o: JsonObject): ModelInfo? {
        val name = o.str("name") ?: return null
        if (o.bool("supportsServerless") != true) return null
        if (o.str("state") != "READY") return null
        if (o.str("kind")?.contains("EMBEDDING", ignoreCase = true) == true) return null
        if (name.contains("reranker", ignoreCase = true)) return null
        val ctx = (o["contextLength"] as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toLongOrNull()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() } ?: 0
        return ModelInfo(
            id = name,
            displayName = o.str("displayName")?.takeIf { it.isNotBlank() } ?: name.substringAfterLast('/'),
            contextLength = ctx,
            supportsImageInput = o.bool("supportsImageInput") ?: false,
            supportsTools = o.bool("supportsTools") ?: false,
        )
    }

    private suspend fun get(url: String, apiKey: String): String {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json").get().build()
        val response = try {
            httpClient.newCall(request).await()
        } catch (e: IOException) {
            throw FireworksException.Network(e)
        }
        return withContext(Dispatchers.IO) {
            try {
                response.use { r ->
                    val text = r.body.string()
                    if (!r.isSuccessful) throw mapError(r.code, text, r.header("Retry-After"))
                    text
                }
            } catch (e: IOException) {
                throw FireworksException.Network(e)
            }
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    // ---------------------------------------------------------------- streamChat

    override fun streamChat(apiKey: String, request: ChatRequest): Flow<StreamEvent> = flow {
        var attempt = 1
        while (true) {
            var started = false
            try {
                streamOnce(apiKey, request).collect {
                    started = true
                    emit(it)
                }
                return@flow
            } catch (e: FireworksException) {
                if (started || attempt >= maxAttempts) throw e
                val wait = retryDelayMs(e, attempt) ?: throw e
                attempt++
                delay(wait)
            }
        }
    }

    /** Délai avant la prochaine tentative, ou null si l'erreur n'est pas relançable (seuls 429 et 5xx le sont). */
    private fun retryDelayMs(e: FireworksException, attempt: Int): Long? {
        val retryAfterMs = when {
            e is FireworksException.RateLimited -> e.retryAfterSeconds?.let { it.coerceAtLeast(0) * 1000L }
            e is FireworksException.Http && e.code in 500..599 -> null
            else -> return null
        }
        if (retryAfterMs != null) return retryAfterMs.takeIf { it <= maxRetryAfterMs }
        // Backoff exponentiel avec jitter: entre la moitié et la totalité de base * 2^(essai-1).
        val ceiling = retryBaseDelayMs shl (attempt - 1).coerceAtMost(10)
        return ceiling / 2 + random.nextLong(ceiling / 2 + 1)
    }

    private fun streamOnce(apiKey: String, request: ChatRequest): Flow<StreamEvent> = callbackFlow {
        val httpRequest = Request.Builder()
            .url("$base/inference/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(buildChatBody(request).toRequestBody(JSON_MEDIA))
            .build()
        val source = EventSources.createFactory(streamClient)
            .newEventSource(httpRequest, SseListener(this))
        awaitClose { source.cancel() }
    }.buffer(Channel.UNLIMITED)

    /** Reçoit les callbacks OkHttp (un seul thread à la fois) et alimente le flux. */
    private class SseListener(private val out: ProducerScope<StreamEvent>) : EventSourceListener() {
        private var finishReason: String? = null
        private var done = false
        private var ended = false

        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            if (ended) return
            val payload = data.trim()
            if (payload.isEmpty()) return
            if (payload == "[DONE]") {
                done = true
                finish()
                return
            }
            val obj = runCatching { LenientJson.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return
            // Erreur embarquée dans le flux.
            if (obj["error"] != null && obj["choices"] == null) {
                fail(mapError(0, payload))
                return
            }
            val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            if (choice != null) {
                val delta = choice.obj("delta")
                val reasoning = delta?.str("reasoning_content") ?: delta?.str("reasoning")
                if (!reasoning.isNullOrEmpty()) out.trySend(StreamEvent.ReasoningDelta(reasoning))
                val content = delta?.str("content")
                if (!content.isNullOrEmpty()) out.trySend(StreamEvent.ContentDelta(content))
                choice.str("finish_reason")?.let { finishReason = it }
            }
            obj.obj("usage")?.let { u ->
                out.trySend(
                    StreamEvent.Usage(
                        promptTokens = u.int("prompt_tokens"),
                        completionTokens = u.int("completion_tokens"),
                        reasoningTokens = u.obj("completion_tokens_details")?.int("reasoning_tokens"),
                    ),
                )
            }
        }

        override fun onClosed(eventSource: EventSource) {
            if (ended) return
            if (finishReason != null) finish() else fail(FireworksException.Network(IOException("Flux interrompu avant la fin de la réponse")))
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            if (ended) return
            // Le corps n'est lu que pour un statut d'erreur ou un content-type invalide (IllegalStateException
            // levée par okhttp-sse); pour une coupure en plein flux, il est déjà inutilisable.
            if (response != null && (!response.isSuccessful || t is IllegalStateException)) {
                val body = runCatching { response.body.string() }.getOrDefault("")
                if (!response.isSuccessful) {
                    fail(mapError(response.code, body, response.header("Retry-After")))
                    return
                }
                // 200 mais pas un flux SSE: erreur JSON éventuelle avant le flux.
                if (body.contains("\"error\"")) {
                    fail(mapError(response.code, body, response.header("Retry-After")))
                    return
                }
            }
            // Coupure après la fin logique de la réponse: on la considère terminée.
            if (finishReason != null) {
                finish()
                return
            }
            fail(FireworksException.Network(t as? IOException ?: IOException(t?.message ?: "Erreur réseau", t)))
        }

        private fun finish() {
            ended = true
            out.trySend(StreamEvent.Finish(finishReason))
            out.close()
        }

        private fun fail(e: FireworksException) {
            ended = true
            out.close(e)
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.fireworks.ai"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** Corps JSON de la requête; un paramètre n'est présent que s'il est non-null (stop: si non vide). */
        internal fun buildChatBody(request: ChatRequest): String {
            val p = request.params
            return buildJsonObject {
                put("model", request.model)
                putJsonArray("messages") {
                    request.messages.forEach { m ->
                        addJsonObject {
                            put("role", m.role)
                            put("content", m.content)
                        }
                    }
                }
                put("stream", true)
                putJsonObject("stream_options") { put("include_usage", true) }
                p.temperature?.let { put("temperature", it) }
                p.topP?.let { put("top_p", it) }
                p.topK?.let { put("top_k", it) }
                p.minP?.let { put("min_p", it) }
                p.maxTokens?.let { put("max_tokens", it) }
                if (p.stop.isNotEmpty()) putJsonArray("stop") { p.stop.take(4).forEach { add(JsonPrimitive(it)) } }
                p.frequencyPenalty?.let { put("frequency_penalty", it) }
                p.presencePenalty?.let { put("presence_penalty", it) }
                p.repetitionPenalty?.let { put("repetition_penalty", it) }
                p.seed?.let { put("seed", it) }
                p.reasoningEffort?.let { put("reasoning_effort", it.api) }
            }.toString()
        }
    }
}
