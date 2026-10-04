package app.fwchat.data.net

import app.fwchat.domain.FireworksException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.net.SocketTimeoutException

internal val LenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    coerceInputValues = true
}

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toBooleanStrictOrNull()

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun parseErrorBody(body: String): Pair<String?, String?> {
    val root = runCatching { LenientJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
        ?: return null to null
    val err = root.obj("error")
    val message = err?.str("message") ?: (root["error"] as? JsonPrimitive)?.contentOrNull ?: root.str("message")
    val code = err?.str("code") ?: root.str("code")
    return message to code
}

/**
 * Convertit une réponse d'erreur HTTP (ou une erreur embarquée dans le flux, [httpCode] = 0)
 * en [FireworksException]. Format Fireworks: `{"error":{"message","code","type","param"}}`.
 */
internal fun mapError(httpCode: Int, body: String, retryAfter: String? = null): FireworksException {
    val (message, code) = parseErrorBody(body)
    val msg = message ?: body.take(300).ifBlank { "HTTP $httpCode" }
    val lower = msg.lowercase()
    val codeUp = code?.uppercase()
    return when {
        httpCode == 401 || httpCode == 403 || codeUp == "UNAUTHORIZED" -> FireworksException.Unauthorized(msg)
        httpCode == 402 || codeUp == "PAYMENT_REQUIRED" || codeUp == "INSUFFICIENT_CREDIT" ->
            FireworksException.InsufficientCredit(msg)
        httpCode == 429 || codeUp == "RESOURCE_EXHAUSTED" || codeUp == "RATE_LIMIT_EXCEEDED" ->
            FireworksException.RateLimited(msg, retryAfter?.trim()?.toIntOrNull())
        httpCode == 404 || codeUp == "NOT_FOUND" -> FireworksException.ModelNotFound(msg)
        isContextError(lower, code?.lowercase()) -> FireworksException.ContextLengthExceeded(msg)
        else -> FireworksException.Http(httpCode, body)
    }
}

private fun isContextError(lowerMessage: String, lowerCode: String?): Boolean =
    lowerCode == "context_length_exceeded" ||
        "context length" in lowerMessage || "context window" in lowerMessage ||
        "maximum context" in lowerMessage || "context_length_exceeded" in lowerMessage ||
        "prompt is too long" in lowerMessage || "input is too long" in lowerMessage

/** Message lisible en français pour l'utilisateur (stocké dans Message.error et affiché en toast). */
fun FireworksException.toUserMessage(): String = when (this) {
    is FireworksException.Unauthorized -> "Clé API invalide ou manquante. Vérifie-la dans les réglages."
    is FireworksException.InsufficientCredit -> "Crédit Fireworks insuffisant. Recharge ton compte puis réessaie."
    is FireworksException.RateLimited -> if (retryAfterSeconds != null) {
        "Trop de requêtes (limite atteinte). Réessaie dans $retryAfterSeconds s."
    } else {
        "Trop de requêtes (limite atteinte). Réessaie dans quelques instants."
    }
    is FireworksException.ModelNotFound ->
        "Modèle introuvable ou indisponible. La liste des modèles a été rechargée: choisis-en un autre."
    is FireworksException.ContextLengthExceeded ->
        "Conversation trop longue pour ce modèle (contexte dépassé). Raccourcis-la ou choisis un modèle à plus grand contexte."
    is FireworksException.Http -> "Erreur du serveur (HTTP $code)" + detailOf(body)
    is FireworksException.Network -> if (generateSequence<Throwable>(this) { it.cause }.any { it is SocketTimeoutException }) {
        "Délai dépassé: le serveur ne répond plus. Vérifie ta connexion et réessaie."
    } else {
        "Erreur réseau: ${cause?.message ?: message ?: "connexion impossible"}"
    }
}

private fun detailOf(body: String): String {
    val (message, _) = parseErrorBody(body)
    val d = (message ?: body).trim().take(200)
    return if (d.isEmpty()) "." else ": $d"
}
