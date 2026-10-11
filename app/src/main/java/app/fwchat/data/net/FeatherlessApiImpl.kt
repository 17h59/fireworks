package app.fwchat.data.net

import app.fwchat.data.DataJson
import app.fwchat.domain.ChatApi
import app.fwchat.domain.ChatRequest
import app.fwchat.domain.FireworksException
import app.fwchat.domain.ModelInfo
import app.fwchat.domain.PlanInfo
import app.fwchat.domain.Provider
import app.fwchat.domain.ReasoningEffort
import app.fwchat.domain.StreamEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.random.Random

/**
 * Client Featherless (https://api.featherless.ai/v1, compatible OpenAI). Aucune dépendance Android.
 *
 * - Liste: `GET /v1/models?status=active&conversational=true&per_page=1000&page=N` (~7 500 modèles, ~2,4 Mo en 8 pages),
 *   décodée en flux (jamais une String de plusieurs Mo). `status=active` est toujours explicite: avec la pagination,
 *   le serveur n'applique plus ce filtre par défaut. Si le serveur refuse le filtre (400/422), repli sur le dump complet.
 * - Chat: `reasoning_effort` est IGNORÉ par Featherless, donc jamais envoyé; « Aucun » raisonnement devient
 *   `chat_template_kwargs` (enable_thinking/thinking/do_reasoning = false), sans effet sur les modèles qui raisonnent toujours.
 * - 429 = limite de CONCURRENCE; relances (429 et 503) avant le flux seulement.
 */
internal class FeatherlessApiImpl(
    client: OkHttpClient,
    baseUrl: String = DEFAULT_BASE_URL,
    streamIdleTimeoutSeconds: Long = 120,
    maxAttempts: Int = 3,
    retryBaseDelayMs: Long = 1000,
    maxRetryAfterMs: Long = 20_000,
    random: Random = Random.Default,
    private val pageSize: Int = PAGE_SIZE,
) : OpenAiCompatClient(client, baseUrl, streamIdleTimeoutSeconds, maxAttempts, retryBaseDelayMs, maxRetryAfterMs, random),
    ChatApi {

    override val provider: Provider = Provider.FEATHERLESS
    override val chatUrl: String get() = "$base/v1/chat/completions"

    override fun Request.Builder.extraHeaders(): Request.Builder = header("X-Title", "FW Chat")

    override fun chatBody(request: ChatRequest): String = buildChatBody(request)
    override fun mapHttpError(httpCode: Int, body: String, retryAfter: String?): FireworksException =
        mapFeatherlessError(httpCode, body, retryAfter)

    override fun isRetryable(e: FireworksException): Boolean =
        e is FireworksException.RateLimited || e is FireworksException.NoCapacity ||
            (e is FireworksException.Http && e.code in 500..599)

    override fun streamChat(apiKey: String, request: ChatRequest): Flow<StreamEvent> =
        stream(apiKey, request).catch { e ->
            // Le message d'erreur ne contient pas toujours l'URL: on la reconstruit depuis l'id du modèle.
            if (e is FireworksException.ModelGated && e.url == null) {
                throw FireworksException.ModelGated(gatedUrl(request.model))
            }
            throw e
        }

    // ---------------------------------------------------------------- plan

    override suspend fun plan(apiKey: String): PlanInfo? {
        val text = getText("$base/v1/plan", apiKey)
        val o = try {
            DataJson.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            throw FireworksException.Network(IOException("Réponse illisible du serveur", e))
        } ?: return null
        return PlanInfo(
            name = o.str("name") ?: o.str("id") ?: "",
            maxContextLength = o["max_context_length"].asInt()?.takeIf { it > 0 },
            concurrency = o["concurrency"].asInt(),
            credits = (o["credits"] as? JsonPrimitive)?.doubleOrNull,
        )
    }

    // ---------------------------------------------------------------- listModels

    override suspend fun listModels(apiKey: String): List<ModelInfo> {
        val out = LinkedHashMap<String, ModelInfo>()
        try {
            var page = 1
            while (true) {
                val url = "$base/v1/models".toHttpUrl().newBuilder()
                    .addQueryParameter("status", "active")
                    .addQueryParameter("conversational", "true")
                    .addQueryParameter("per_page", pageSize.toString())
                    .addQueryParameter("page", page.toString())
                    .build()
                val result = fetchPage(url.toString(), apiKey, out)
                val total = result.pagination?.totalPages ?: 1
                if (result.count == 0 || page >= total || page >= MAX_PAGES) break
                page++
            }
        } catch (e: FireworksException.Http) {
            // Filtre ou pagination refusés: dump complet (plus lourd mais toujours valable).
            if (e.code != 400 && e.code != 422) throw e
            out.clear()
            fetchPage("$base/v1/models", apiKey, out)
        }
        return out.values.toList()
    }

    private class PageResult(val count: Int, val pagination: PaginationDto?)

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun fetchPage(url: String, apiKey: String, out: MutableMap<String, ModelInfo>): PageResult {
        val response = getResponse(url, apiKey.takeIf { it.isNotBlank() })
        return withContext(Dispatchers.IO) {
            try {
                response.use { r ->
                    val page = DataJson.decodeFromStream<ModelsPage>(r.body.byteStream())
                    for (dto in page.data) {
                        val m = dto.toModelInfo() ?: continue
                        out[m.id] = m
                    }
                    PageResult(page.data.size, page.pagination)
                }
            } catch (e: IOException) {
                throw FireworksException.Network(e)
            } catch (e: SerializationException) {
                throw FireworksException.Network(IOException("Réponse illisible du serveur", e))
            } catch (e: IllegalArgumentException) {
                throw FireworksException.Network(IOException("Réponse illisible du serveur", e))
            }
        }
    }

    // ---------------------------------------------------------------- DTO (tolérants: tout est optionnel)

    @Serializable
    private class ModelsPage(
        val data: List<ModelDto> = emptyList(),
        val pagination: PaginationDto? = null,
    )

    @Serializable
    private class PaginationDto(
        @SerialName("current_page") val currentPage: Int? = null,
        @SerialName("total_pages") val totalPages: Int? = null,
    )

    @Serializable
    private class ModelDto(
        val id: String? = null,
        @SerialName("is_gated") val isGated: JsonElement? = null,
        @SerialName("context_length") val contextLength: JsonElement? = null,
        @SerialName("concurrency_cost") val concurrencyCost: JsonElement? = null,
        @SerialName("available_on_current_plan") val availableOnPlan: JsonElement? = null,
        val features: JsonElement? = null,
    ) {
        fun toModelInfo(): ModelInfo? {
            val id = id?.takeIf { it.isNotBlank() } ?: return null
            val f = features as? JsonObject
            if (f.flag("audio_output") || f.flag("classifier")) return null
            if (id.contains("embedding", ignoreCase = true)) return null
            return ModelInfo(
                id = id,
                displayName = id.substringAfterLast('/'),
                contextLength = contextLength.asInt()?.coerceAtLeast(0) ?: 0,
                supportsImageInput = f.flag("image_input"),
                supportsTools = f.flag("tool_use"),
                gated = isGated.asBool() == true,
                availableOnPlan = availableOnPlan.asBool(),
                concurrencyCost = concurrencyCost.asInt(),
            )
        }

        private fun JsonObject?.flag(key: String): Boolean = this?.get(key).asBool() == true
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.featherless.ai"
        const val PAGE_SIZE = 1000
        private const val MAX_PAGES = 60

        internal fun gatedUrl(modelId: String) = "https://featherless.ai/models/$modelId"

        /** Corps JSON; `reasoning_effort` n'est JAMAIS envoyé (ignoré par Featherless). */
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
                if (p.stop.isNotEmpty()) putJsonArray("stop") { p.stop.forEach { add(JsonPrimitive(it)) } }
                p.frequencyPenalty?.let { put("frequency_penalty", it) }
                p.presencePenalty?.let { put("presence_penalty", it) }
                p.repetitionPenalty?.let { put("repetition_penalty", it) }
                p.seed?.let { put("seed", it) }
                // Seul « Aucun » a une traduction; les autres niveaux laissent le défaut du modèle.
                if (p.reasoningEffort == ReasoningEffort.NONE) {
                    putJsonObject("chat_template_kwargs") {
                        put("enable_thinking", false)
                        put("thinking", false)
                        put("do_reasoning", false)
                    }
                }
            }.toString()
        }
    }
}

private fun JsonElement?.asInt(): Int? {
    val p = this as? JsonPrimitive ?: return null
    p.contentOrNull ?: return null
    p.content.toIntOrNull()?.let { return it }
    return p.doubleOrNull?.takeIf { it.isFinite() }?.coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble())?.toInt()
}

private fun JsonElement?.asBool(): Boolean? =
    (this as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toBooleanStrictOrNull()

private val URL_REGEX = Regex("""https?://[^\s"'<>)\]]+""")

/** Erreurs Featherless: `{"error":{"message","type","param","code"}}`. */
internal fun mapFeatherlessError(httpCode: Int, body: String, retryAfter: String? = null): FireworksException {
    val root = runCatching { DataJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
    val err = root?.obj("error")
    val message = err?.str("message") ?: (root?.get("error") as? JsonPrimitive)?.contentOrNull
        ?: root?.str("message") ?: body.take(300).ifBlank { "HTTP $httpCode" }
    val code = (err?.str("code") ?: root?.str("code"))?.lowercase()
    val lower = message.lowercase()
    return when {
        httpCode == 401 || code == "unauthorized" -> FireworksException.Unauthorized(message)
        httpCode == 402 || code == "payment_required" -> FireworksException.InsufficientCredit(message)
        code == "model_gated_needs_oauth" ->
            FireworksException.ModelGated(URL_REGEX.find(message)?.value?.trimEnd('.', ',', ';'), message)
        httpCode == 403 -> FireworksException.ModelNotInPlan(message)
        httpCode == 404 || code == "model_not_found" -> FireworksException.ModelNotFound(message)
        httpCode == 429 || code == "rate_limit_exceeded" ->
            FireworksException.RateLimited(message, retryAfter?.trim()?.toIntOrNull(), concurrency = true)
        httpCode == 400 && ("cold" in lower || "not ready" in lower) -> FireworksException.ModelCold(message)
        httpCode == 503 -> FireworksException.NoCapacity(message)
        isContextErrorText(lower, code) -> FireworksException.ContextLengthExceeded(message)
        else -> FireworksException.Http(httpCode, body)
    }
}
