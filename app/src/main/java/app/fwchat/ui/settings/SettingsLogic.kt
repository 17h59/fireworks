package app.fwchat.ui.settings

import app.fwchat.domain.FireworksException
import app.fwchat.domain.ModelInfo
import app.fwchat.domain.SystemPrompt
import app.fwchat.ui.common.formatContext
import java.io.IOException
import java.time.LocalDate

/** Libellé affiché pour « aucun prompt système ». */
const val NO_SYSTEM_PROMPT_LABEL = "Aucun prompt système"

/**
 * Motif d'affichage de la clé: « •••• » suivi des 4 derniers caractères si la clé est assez longue
 * (jamais plus de 4 caractères révélés, et rien pour une clé courte). null = pas de clé.
 */
fun maskApiKey(key: String?): String? {
    val k = key?.trim().orEmpty()
    if (k.isEmpty()) return null
    return if (k.length >= 12) "••••" + k.takeLast(4) else "••••"
}

/** Nom du prompt par défaut: « Aucun prompt système », ou son nom (un id qui ne correspond plus à rien vaut « aucun »). */
fun promptLabel(defaultId: String?, prompts: List<SystemPrompt>): String {
    if (defaultId == null) return NO_SYSTEM_PROMPT_LABEL
    val prompt = prompts.firstOrNull { it.id == defaultId } ?: return NO_SYSTEM_PROMPT_LABEL
    return prompt.name.ifBlank { "Sans nom" }
}

/** true si le prompt par défaut pointe vers un prompt qui n'existe plus (à remettre à « aucun »). */
fun isStaleDefaultPrompt(defaultId: String?, prompts: List<SystemPrompt>): Boolean =
    defaultId != null && prompts.none { it.id == defaultId }

/** Ligne de résumé du modèle par défaut: nom court + contexte (ex. « glm-5p3-flash · contexte 128k »). */
fun modelSummary(modelId: String?, models: List<ModelInfo>): String {
    if (modelId == null) return "Aucun modèle choisi"
    val info = models.firstOrNull { it.id == modelId }
        ?: return modelId.substringAfterLast('/') + " · indisponible dans la liste actuelle"
    val ctx = formatContext(info.contextLength)?.let { "contexte $it" } ?: "contexte inconnu"
    return "${info.shortId} · $ctx"
}

/** Nombre de modèles, au singulier ou au pluriel. */
fun modelCountLabel(count: Int): String = when (count) {
    0 -> "Aucun modèle disponible"
    1 -> "1 modèle disponible"
    else -> "$count modèles disponibles"
}

/** Message lisible pour une erreur de vérification de clé ou de rechargement des modèles. */
fun describeNetworkError(e: Throwable?): String = when (e) {
    is FireworksException.Unauthorized ->
        "Cette clé a été refusée par Fireworks. Vérifie qu'elle est complète et toujours active."
    is FireworksException.InsufficientCredit -> "Crédit Fireworks insuffisant. Recharge ton compte puis réessaie."
    is FireworksException.RateLimited -> "Trop de requêtes pour le moment. Réessaie dans un instant."
    is FireworksException.Network, is IOException ->
        "Impossible de joindre Fireworks. Vérifie ta connexion et réessaie."
    is FireworksException.Http -> "Fireworks a répondu par une erreur (HTTP ${e.code}). Réessaie dans un instant."
    else -> "L'opération a échoué. Réessaie dans un instant."
}

/** Nom de fichier proposé à l'export: fwchat-sauvegarde-AAAA-MM-JJ.json. */
fun backupFileName(date: LocalDate): String = "fwchat-sauvegarde-$date.json"
