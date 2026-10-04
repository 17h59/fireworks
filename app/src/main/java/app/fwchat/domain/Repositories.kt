package app.fwchat.domain

import kotlinx.coroutines.flow.Flow

/** Implémenté par la couche data (Room). Voir docs/ARCHITECTURE.md pour la sémantique des branches. */
interface ChatRepository {
    /** query vide = tout. Recherche sur le titre, insensible à la casse et aux accents. Tri updatedAt desc. */
    fun observeChatSummaries(query: String): Flow<List<ChatSummary>>
    fun observeChat(chatId: String): Flow<Chat?>
    /** Chemin actif (racine -> feuille) en suivant selectedChildId, avec les frères de chaque nœud. */
    fun observeThread(chatId: String): Flow<List<ThreadItem>>

    suspend fun getChat(chatId: String): Chat?
    /** Chemin actif sans les infos de frères (pour construire une requête API). */
    suspend fun getActivePath(chatId: String): List<Message>
    suspend fun getMessage(messageId: String): Message?

    /** Crée un chat vide; le prompt système est copié (snapshot). */
    suspend fun createChat(modelId: String, systemPrompt: SystemPrompt?, params: GenParams): String
    suspend fun renameChat(chatId: String, title: String)
    suspend fun deleteChat(chatId: String)
    /** Change modèle et/ou paramètres. Ne touche JAMAIS au prompt système. */
    suspend fun updateChatSettings(chatId: String, modelId: String? = null, params: GenParams? = null)
    /** Clone le chemin actif de la racine jusqu'à [uptoMessageId] inclus dans un nouveau chat "Fork de <titre>". Retourne l'id du nouveau chat. */
    suspend fun fork(chatId: String, uptoMessageId: String): String

    /** Ajoute un message utilisateur enfant de [parentId] (null = racine), le sélectionne. Si le titre du chat est vide, le déduit du texte. */
    suspend fun addUserMessage(chatId: String, parentId: String?, content: String): Message
    /** Message assistant vide en status STREAMING, enfant de [parentId], sélectionné. */
    suspend fun addAssistantPlaceholder(chatId: String, parentId: String, modelId: String): Message
    /** Checkpoint pendant le streaming (appelé ~1/s). */
    suspend fun updateStreaming(messageId: String, content: String, reasoning: String?)
    suspend fun finishMessage(
        messageId: String, status: MessageStatus, finishReason: String?, error: String?,
        promptTokens: Int?, completionTokens: Int?, reasoningTokens: Int?,
    )
    /** Modifie le contenu sans créer de branche ni régénérer; edited = true. */
    suspend fun editInPlace(messageId: String, content: String)
    /** Édition d'un message utilisateur avec renvoi: nouveau frère (même parent), sélectionné. Retourne le nouveau message. */
    suspend fun editUserAsBranch(messageId: String, newContent: String): Message
    /** Régénération: nouveau frère assistant (même parent) en STREAMING, sélectionné. */
    suspend fun addAssistantSibling(messageId: String, modelId: String): Message
    /** Sélectionne ce message parmi ses frères (flèches < >). */
    suspend fun selectSibling(messageId: String)
    /** Supprime le message et tout son sous-arbre; la sélection du parent retombe sur un frère restant. */
    suspend fun deleteSubtree(messageId: String)
    /** Au démarrage de l'app: les messages restés STREAMING (process tué) passent INTERRUPTED. */
    suspend fun recoverInterrupted()

    /** Sauvegarde/restauration complète en JSON (tout est local, donc indispensable). */
    suspend fun exportAll(): String
    suspend fun importAll(json: String, replace: Boolean)
}

interface SystemPromptRepository {
    fun observeAll(): Flow<List<SystemPrompt>>
    suspend fun get(id: String): SystemPrompt?
    /** id vide = création. Retourne l'id. */
    suspend fun upsert(prompt: SystemPrompt): String
    suspend fun delete(id: String)
}

data class AppSettings(
    val hasApiKey: Boolean,
    val defaultModelId: String?,
    /** null = "Aucun prompt système" par défaut. */
    val defaultSystemPromptId: String?,
    val defaultParams: GenParams,
)

interface SettingsRepository {
    val settings: Flow<AppSettings>
    /** Clé stockée chiffrée (Android Keystore). */
    suspend fun apiKey(): String?
    suspend fun setApiKey(key: String?)
    suspend fun setDefaultModel(modelId: String?)
    suspend fun setDefaultSystemPrompt(promptId: String?)
    suspend fun setDefaultParams(params: GenParams)
}

interface ModelRepository {
    /** Modèles de chat serverless en cache local (triés), émis dès le démarrage. */
    val models: Flow<List<ModelInfo>>
    /** Recharge depuis l'API (au démarrage et à chaque erreur liée aux modèles). Met à jour le cache. */
    suspend fun refresh(): Result<Unit>
}
