package app.fwchat.ui.chat

import app.fwchat.domain.GenParams
import app.fwchat.domain.Message
import app.fwchat.domain.MessageStatus
import app.fwchat.domain.Role
import app.fwchat.domain.StreamingText
import app.fwchat.domain.SystemPrompt
import app.fwchat.ui.common.formatInt
import app.fwchat.ui.prompts.resolvePlaceholders
import java.time.ZonedDateTime

// ------------------------------------------------------------------------------------------------
// Bloc « réflexion » (thinking): machine d'états pure.

enum class ThinkingMode { COLLAPSED, PREVIEW, FULL }

/**
 * États du bloc de réflexion.
 * - Défaut: aperçu pendant que le message streame, replié sinon (messages terminés / historiques).
 * - Le hamburger replie complètement quand le bloc est ouvert (aperçu ou complet), et rouvre en aperçu quand il est replié.
 * - « Tout afficher » passe de l'aperçu au complet; « Réduire » repasse du complet à l'aperçu.
 * Un choix explicite de l'utilisateur (override) l'emporte sur le défaut, y compris quand le message se termine.
 */
object ThinkingStates {
    private const val PREVIEW_LINES = 5

    fun default(messageStreaming: Boolean): ThinkingMode =
        if (messageStreaming) ThinkingMode.PREVIEW else ThinkingMode.COLLAPSED

    fun resolve(override: ThinkingMode?, messageStreaming: Boolean): ThinkingMode =
        override ?: default(messageStreaming)

    fun onHamburger(current: ThinkingMode): ThinkingMode =
        if (current == ThinkingMode.COLLAPSED) ThinkingMode.PREVIEW else ThinkingMode.COLLAPSED

    fun onShowAll(current: ThinkingMode): ThinkingMode =
        if (current == ThinkingMode.PREVIEW) ThinkingMode.FULL else current

    fun onShrink(current: ThinkingMode): ThinkingMode =
        if (current == ThinkingMode.FULL) ThinkingMode.PREVIEW else current

    /** Le modèle réfléchit encore: message en cours et pas encore de contenu de réponse. */
    fun isThinking(status: MessageStatus, content: String): Boolean =
        status == MessageStatus.STREAMING && content.isEmpty()

    /** Estimation bon marché: l'aperçu (5 lignes) risque de couper le texte -> proposer « Tout afficher ». */
    fun mayOverflowPreview(reasoning: String): Boolean {
        if (reasoning.length > 260) return true
        var lines = 1
        for (c in reasoning) if (c == '\n' && ++lines > PREVIEW_LINES) return true
        return false
    }
}

// ------------------------------------------------------------------------------------------------
// Brouillon d'un nouveau chat (en mémoire, rien en base avant le 1er envoi).

data class ChatDraft(
    val modelId: String?,
    val prompt: SystemPrompt?,
    val params: GenParams,
) {
    /**
     * Copie du prompt choisi dont le texte a ses balises {{date}}, {{heure}}, {{date_iso}} résolues.
     * C'est ce snapshot (jamais le prompt de la bibliothèque) qui est figé dans le chat.
     */
    fun promptSnapshot(now: ZonedDateTime = ZonedDateTime.now()): SystemPrompt? =
        prompt?.copy(text = resolvePlaceholders(prompt.text, now))
}

// ------------------------------------------------------------------------------------------------
// Mise en forme

object ConversationFormatter {

    /**
     * Conversation en texte lisible (pour « Copier toute la conversation »).
     * [live] remplace le contenu des messages encore en streaming.
     */
    fun format(
        title: String,
        modelId: String?,
        promptName: String?,
        messages: List<Message>,
        live: Map<String, StreamingText> = emptyMap(),
    ): String = buildString {
        append(title.ifBlank { "Nouveau chat" })
        append('\n')
        if (modelId != null) append("Modèle : ").append(modelId.substringAfterLast('/')).append('\n')
        if (promptName != null) append("Prompt système : ").append(promptName).append('\n')
        for (m in messages) {
            append('\n')
            when (m.role) {
                Role.USER -> append("Vous :\n")
                Role.ASSISTANT -> {
                    append("Assistant")
                    m.modelId?.let { append(" (").append(it.substringAfterLast('/')).append(')') }
                    append(" :\n")
                }
            }
            val text = live[m.id]?.content ?: m.content
            append(text.trimEnd())
            when (m.status) {
                MessageStatus.ERROR -> append("\n[Erreur : ").append(m.error ?: "inconnue").append(']')
                MessageStatus.INTERRUPTED -> append("\n[Interrompu]")
                else -> Unit
            }
            append('\n')
        }
    }.trimEnd() + "\n"

    /** Ligne discrète sous une réponse terminée: « glm-5p3 · 1 240 tokens ». */
    fun meta(message: Message): String? {
        val parts = ArrayList<String>(3)
        message.modelId?.let { parts += it.substringAfterLast('/') }
        message.completionTokens?.let { parts += "${formatInt(it)} tokens" }
        if (message.edited) parts += "modifié"
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }
}
