package app.fwchat.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class ApiMessage(val role: String, val content: String)

data class ChatRequest(
    val model: String,
    /** Premier élément = system si le chat a un prompt système; jamais de system sinon. */
    val messages: List<ApiMessage>,
    val params: GenParams,
)

sealed interface StreamEvent {
    data class ReasoningDelta(val text: String) : StreamEvent
    data class ContentDelta(val text: String) : StreamEvent
    data class Usage(val promptTokens: Int?, val completionTokens: Int?, val reasoningTokens: Int?) : StreamEvent
    data class Finish(val reason: String?) : StreamEvent
}

sealed class FireworksException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unauthorized(msg: String) : FireworksException(msg)
    class InsufficientCredit(msg: String) : FireworksException(msg)
    class RateLimited(msg: String, val retryAfterSeconds: Int?) : FireworksException(msg)
    /** 404 / NOT_FOUND sur le modèle: déclenche un rechargement de la liste. */
    class ModelNotFound(msg: String) : FireworksException(msg)
    class ContextLengthExceeded(msg: String) : FireworksException(msg)
    class Http(val code: Int, val body: String) : FireworksException("HTTP $code: $body")
    class Network(cause: Throwable) : FireworksException(cause.message ?: "Erreur réseau", cause)
}

/** Client Fireworks. Aucune dépendance Android. Les erreurs sont des [FireworksException]. */
interface FireworksApi {
    /** Modèles de chat serverless prêts (supportsServerless, state READY, pas d'embedding/reranker). Pagination gérée. */
    suspend fun listModels(apiKey: String): List<ModelInfo>
    /** SSE. Le Flow se termine après [StreamEvent.Finish]; annuler la collecte ferme la connexion. */
    fun streamChat(apiKey: String, request: ChatRequest): Flow<StreamEvent>
}

sealed interface EngineEvent {
    data object Unauthorized : EngineEvent
    /** [chatId] = chat concerné (null = erreur globale). L'UI n'affiche que les erreurs du chat visible. */
    data class Error(val message: String, val chatId: String? = null) : EngineEvent
}

/**
 * Orchestre la génération. Tourne dans un scope applicatif: la génération survit à la navigation.
 * Toutes les méthodes sont non bloquantes (fire-and-forget); l'état se lit via les flows.
 */
interface ChatEngine {
    /** messageId -> texte live. Absent = pas en streaming (lire la DB). */
    val streaming: StateFlow<Map<String, StreamingText>>
    /** Ids des chats en cours de génération. */
    val generatingChats: StateFlow<Set<String>>
    val events: SharedFlow<EngineEvent>

    /** Ajoute le message utilisateur à la fin du chemin actif puis génère. */
    fun send(chatId: String, text: String)
    /** Édition + renvoi: crée la branche (editUserAsBranch) puis génère. */
    fun editAndResend(userMessageId: String, newText: String)
    /** Nouvelle réponse alternative à ce message assistant (branche). */
    fun regenerate(assistantMessageId: String)
    /** Interrompt: le texte déjà reçu est conservé (status INTERRUPTED). */
    fun stop(chatId: String)
}
