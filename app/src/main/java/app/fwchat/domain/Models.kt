package app.fwchat.domain

import kotlinx.serialization.Serializable

/** Le prompt système n'est PAS un message: il est figé dans [Chat]. */
enum class Role { USER, ASSISTANT }

enum class MessageStatus { COMPLETE, STREAMING, INTERRUPTED, ERROR }

enum class ReasoningEffort(val api: String) {
    NONE("none"), LOW("low"), MEDIUM("medium"), HIGH("high"), MAX("max")
}

/** Famille de modèles, déduite de l'id du modèle. Sert aux modèles de prompt et à l'affichage. */
enum class PromptFamily(val label: String) {
    GLM("GLM (Z.ai)"), DEEPSEEK("DeepSeek"), QWEN("Qwen"), KIMI("Kimi"),
    GPT_OSS("gpt-oss"), MINIMAX("MiniMax"), NEMOTRON("Nemotron"), OTHER("Autre");

    companion object {
        fun fromModelId(id: String): PromptFamily {
            val s = id.lowercase()
            return when {
                "glm" in s -> GLM
                "deepseek" in s -> DEEPSEEK
                "qwen" in s -> QWEN
                "kimi" in s -> KIMI
                "gpt-oss" in s -> GPT_OSS
                "minimax" in s -> MINIMAX
                "nemotron" in s -> NEMOTRON
                else -> OTHER
            }
        }
    }
}

/** Paramètres de génération. null = non envoyé (défaut du modèle). */
@Serializable
data class GenParams(
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val minP: Double? = null,
    val maxTokens: Int? = null,
    val stop: List<String> = emptyList(),
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null,
    val repetitionPenalty: Double? = null,
    val seed: Long? = null,
    val reasoningEffort: ReasoningEffort? = null,
)

object AppDefaults {
    /** Les modèles à raisonnement consomment max_tokens: valeur généreuse par défaut. */
    val GEN_PARAMS = GenParams(maxTokens = 16384)
    const val MODEL_ID_PREFIX = "accounts/fireworks/models/"
}

data class ModelInfo(
    /** Id complet, ex: accounts/fireworks/models/glm-5p3 */
    val id: String,
    val displayName: String,
    val contextLength: Int,
    val supportsImageInput: Boolean,
    val supportsTools: Boolean,
) {
    val family: PromptFamily get() = PromptFamily.fromModelId(id)
    val shortId: String get() = id.substringAfterLast('/')
}

data class SystemPrompt(
    val id: String,
    val name: String,
    val text: String,
    val family: PromptFamily?,
    val createdAt: Long,
    val updatedAt: Long,
)

data class Chat(
    val id: String,
    /** Vide = pas encore titré (l'UI affiche "Nouveau chat"). Rempli auto au 1er message utilisateur. */
    val title: String,
    val modelId: String,
    /** Snapshot figé à la création. null = aucun prompt système. Jamais modifié ensuite. */
    val systemPromptName: String?,
    val systemPromptText: String?,
    val params: GenParams,
    /** Racine sélectionnée parmi les messages sans parent (plusieurs si le 1er message a été édité en branche). */
    val selectedRootId: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

data class ChatSummary(
    val id: String,
    val title: String,
    val modelId: String,
    val updatedAt: Long,
    val messageCount: Int,
)

data class Message(
    val id: String,
    val chatId: String,
    val parentId: String?,
    val role: Role,
    val content: String,
    /** Texte de réflexion (thinking), assistant uniquement. */
    val reasoning: String?,
    /** Enfant actuellement sélectionné (mémorise la branche active sous ce nœud). */
    val selectedChildId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    /** Modèle ayant généré (assistant). */
    val modelId: String?,
    val status: MessageStatus,
    val finishReason: String?,
    val error: String?,
    /** true si modifié à la main (en place). */
    val edited: Boolean,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val reasoningTokens: Int?,
)

/** Un message du chemin actif + ses frères (pour les flèches < 2/3 >). */
data class ThreadItem(
    val message: Message,
    /** Ids de tous les frères (y compris ce message), triés par createdAt. */
    val siblingIds: List<String>,
) {
    val siblingIndex: Int get() = siblingIds.indexOf(message.id)
    val siblingCount: Int get() = siblingIds.size
}

/** Texte en cours de streaming, tenu en mémoire (la DB n'est mise à jour que par checkpoints). */
data class StreamingText(val content: String, val reasoning: String)
