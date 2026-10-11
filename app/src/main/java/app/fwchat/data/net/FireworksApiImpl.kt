package app.fwchat.data.net

import app.fwchat.data.DataJson
import app.fwchat.domain.ChatApi
import app.fwchat.domain.ChatRequest
import app.fwchat.domain.FireworksException
import app.fwchat.domain.ModelInfo
import app.fwchat.domain.Provider
import app.fwchat.domain.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import kotlin.random.Random

/**
 * Client Fireworks (OkHttp). Aucune dépendance Android.
 *
 * @param baseUrl racine sans slash final (injectable pour MockWebServer).
 * @param streamIdleTimeoutSeconds délai max sans aucun octet reçu pendant le streaming (readTimeout du client SSE).
 * Relances et backoff: voir [OpenAiCompatClient].
 */
internal class FireworksApiImpl(
    client: OkHttpClient,
    baseUrl: String = DEFAULT_BASE_URL,
    streamIdleTimeoutSeconds: Long = 120,
    maxAttempts: Int = 3,
    retryBaseDelayMs: Long = 1000,
    maxRetryAfterMs: Long = 20_000,
    random: Random = Random.Default,
) : OpenAiCompatClient(client, baseUrl, streamIdleTimeoutSeconds, maxAttempts, retryBaseDelayMs, maxRetryAfterMs, random),
    ChatApi {

    override val provider: Provider = Provider.FIREWORKS
    override val chatUrl: String get() = "$base/inference/v1/chat/completions"

    override fun chatBody(request: ChatRequest): String = buildChatBody(request)
    override fun mapHttpError(httpCode: Int, body: String, retryAfter: String?): FireworksException =
        mapError(httpCode, body, retryAfter)

    override fun streamChat(apiKey: String, request: ChatRequest): Flow<StreamEvent> = stream(apiKey, request)

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
                getText(url.toString(), apiKey)
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
        DataJson.parseToJsonElement(body) as? JsonObject ?: throw IllegalArgumentException("not an object")
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

    companion object {
        const val DEFAULT_BASE_URL = "https://api.fireworks.ai"

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
