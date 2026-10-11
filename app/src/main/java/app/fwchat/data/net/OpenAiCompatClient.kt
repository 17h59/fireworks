package app.fwchat.data.net

import app.fwchat.data.DataJson
import app.fwchat.domain.ChatRequest
import app.fwchat.domain.FireworksException
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
import okhttp3.Call
import okhttp3.Callback
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
 * Socle commun aux fournisseurs « compatibles OpenAI » (Fireworks, Featherless): streaming SSE, relances avant le
 * flux, requêtes GET. Les sous-classes fournissent l'URL, le corps de requête et le mapping d'erreurs.
 *
 * @param maxAttempts nombre maximal d'essais d'une requête de chat (1 = pas de relance). Une relance n'a lieu que sur
 * 429 ou 5xx survenus AVANT le premier événement du flux; jamais après un delta reçu.
 * @param retryBaseDelayMs délai de base du backoff exponentiel (avec jitter); `Retry-After` est respecté s'il est présent.
 * @param maxRetryAfterMs au-delà de cette attente demandée par le serveur, on ne relance pas et l'erreur est remontée.
 */
internal abstract class OpenAiCompatClient(
    client: OkHttpClient,
    baseUrl: String,
    streamIdleTimeoutSeconds: Long,
    private val maxAttempts: Int,
    private val retryBaseDelayMs: Long,
    private val maxRetryAfterMs: Long,
    private val random: Random,
) {
    protected val base: String = baseUrl.trimEnd('/')
    protected val httpClient: OkHttpClient = client
    private val streamClient: OkHttpClient = client.newBuilder()
        .readTimeout(streamIdleTimeoutSeconds, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    /** URL complète de l'endpoint de chat. */
    protected abstract val chatUrl: String
    protected abstract fun chatBody(request: ChatRequest): String
    protected abstract fun mapHttpError(httpCode: Int, body: String, retryAfter: String?): FireworksException

    /** En-têtes ajoutés à toute requête (en plus de l'autorisation). */
    protected open fun Request.Builder.extraHeaders(): Request.Builder = this

    /** Erreurs relançables AVANT le premier événement du flux. */
    protected open fun isRetryable(e: FireworksException): Boolean =
        e is FireworksException.RateLimited || (e is FireworksException.Http && e.code in 500..599)

    // ---------------------------------------------------------------- GET

    /** Corps texte d'un GET; [apiKey] null = sans authentification. */
    protected suspend fun getText(url: String, apiKey: String?): String {
        val response = getResponse(url, apiKey)
        return withContext(Dispatchers.IO) {
            try {
                response.use { it.body.string() }
            } catch (e: IOException) {
                throw FireworksException.Network(e)
            }
        }
    }

    /** GET dont la réponse (déjà vérifiée: 2xx) est à lire puis fermer par l'appelant, hors du thread principal. */
    protected suspend fun getResponse(url: String, apiKey: String?): Response {
        val request = Request.Builder().url(url)
            .apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }
            .header("Accept", "application/json").extraHeaders().get().build()
        val response = try {
            httpClient.newCall(request).await()
        } catch (e: IOException) {
            throw FireworksException.Network(e)
        }
        if (!response.isSuccessful) {
            val (code, retryAfter) = response.code to response.header("Retry-After")
            val text = withContext(Dispatchers.IO) {
                try {
                    response.use { it.body.string() }
                } catch (e: IOException) {
                    throw FireworksException.Network(e)
                }
            }
            throw mapHttpError(code, text, retryAfter)
        }
        return response
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

    // ---------------------------------------------------------------- streaming

    protected fun stream(apiKey: String, request: ChatRequest): Flow<StreamEvent> = flow {
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

    /** Délai avant la prochaine tentative, ou null si l'erreur n'est pas relançable. */
    private fun retryDelayMs(e: FireworksException, attempt: Int): Long? {
        if (!isRetryable(e)) return null
        val retryAfterMs = (e as? FireworksException.RateLimited)?.retryAfterSeconds?.let { it.coerceAtLeast(0) * 1000L }
        if (retryAfterMs != null) return retryAfterMs.takeIf { it <= maxRetryAfterMs }
        // Backoff exponentiel avec jitter: entre la moitié et la totalité de base * 2^(essai-1).
        val ceiling = retryBaseDelayMs shl (attempt - 1).coerceAtMost(10)
        return ceiling / 2 + random.nextLong(ceiling / 2 + 1)
    }

    private fun streamOnce(apiKey: String, request: ChatRequest): Flow<StreamEvent> = callbackFlow {
        val httpRequest = Request.Builder()
            .url(chatUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .extraHeaders()
            .post(chatBody(request).toRequestBody(JSON_MEDIA))
            .build()
        val source = EventSources.createFactory(streamClient)
            .newEventSource(httpRequest, SseListener(this, ::mapHttpError))
        awaitClose { source.cancel() }
    }.buffer(Channel.UNLIMITED)

    /**
     * Reçoit les callbacks OkHttp (un seul thread à la fois) et alimente le flux. Les lignes de commentaire SSE
     * (`: FEATHERLESS PROCESSING`) sont ignorées par okhttp-sse; un chunk sans choix ne porte que l'usage.
     */
    private class SseListener(
        private val out: ProducerScope<StreamEvent>,
        private val mapError: (Int, String, String?) -> FireworksException,
    ) : EventSourceListener() {
        private var finishReason: String? = null
        private var ended = false

        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            if (ended) return
            val payload = data.trim()
            if (payload.isEmpty()) return
            if (payload == "[DONE]") {
                finish()
                return
            }
            val obj = runCatching { DataJson.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return
            // Erreur embarquée dans le flux.
            if (obj["error"] != null && obj["choices"] == null) {
                fail(mapError(0, payload, null))
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
                        // Absent chez Featherless (null).
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

    protected companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
