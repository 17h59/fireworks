package app.fwchat.ui

import app.fwchat.domain.AppDefaults
import app.fwchat.domain.AppSettings
import app.fwchat.domain.Chat
import app.fwchat.domain.ChatEngine
import app.fwchat.domain.ChatRepository
import app.fwchat.domain.ChatSummary
import app.fwchat.domain.EngineEvent
import app.fwchat.domain.GenParams
import app.fwchat.domain.Message
import app.fwchat.domain.MessageStatus
import app.fwchat.domain.ModelInfo
import app.fwchat.domain.ModelRepository
import app.fwchat.domain.Role
import app.fwchat.domain.SettingsRepository
import app.fwchat.domain.StreamingText
import app.fwchat.domain.SystemPrompt
import app.fwchat.domain.SystemPromptRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

fun summary(id: String, title: String = id, updatedAt: Long = 0, count: Int = 2, model: String = "accounts/fireworks/models/glm-5p3") =
    ChatSummary(id, title, model, updatedAt, count)

fun model(id: String) = ModelInfo(
    id = "accounts/fireworks/models/$id", displayName = id, contextLength = 1000,
    supportsImageInput = false, supportsTools = false,
)

fun systemPrompt(id: String, name: String = id, text: String = "texte") =
    SystemPrompt(id, name, text, family = null, createdAt = 1, updatedAt = 1)

class FakeSettingsRepo(
    var key: String? = null,
    defaultModel: String? = null,
    defaultPrompt: String? = null,
) : SettingsRepository {
    private val state = MutableStateFlow(
        AppSettings(key != null, defaultModel, defaultPrompt, AppDefaults.GEN_PARAMS),
    )
    override val settings: Flow<AppSettings> = state
    val current: AppSettings get() = state.value
    override suspend fun apiKey(): String? = key
    override suspend fun setApiKey(key: String?) {
        this.key = key
        state.value = state.value.copy(hasApiKey = key != null)
    }
    override suspend fun setDefaultModel(modelId: String?) { state.value = state.value.copy(defaultModelId = modelId) }
    override suspend fun setDefaultSystemPrompt(promptId: String?) {
        state.value = state.value.copy(defaultSystemPromptId = promptId)
    }
    override suspend fun setDefaultParams(params: GenParams) { state.value = state.value.copy(defaultParams = params) }
}

class FakeModelRepo(
    var list: List<ModelInfo> = emptyList(),
    /** Résultat du prochain refresh; null = succès. */
    var failure: Throwable? = null,
) : ModelRepository {
    private val state = MutableStateFlow<List<ModelInfo>>(emptyList())
    var refreshCount = 0
    override val models: Flow<List<ModelInfo>> = state
    override suspend fun refresh(): Result<Unit> {
        refreshCount++
        failure?.let { return Result.failure(it) }
        state.value = list
        return Result.success(Unit)
    }
}

class FakePromptRepo(initial: List<SystemPrompt> = emptyList()) : SystemPromptRepository {
    val items = MutableStateFlow(initial)
    var nextId = 1
    override fun observeAll(): Flow<List<SystemPrompt>> = items
    override suspend fun get(id: String): SystemPrompt? = items.value.firstOrNull { it.id == id }
    override suspend fun upsert(prompt: SystemPrompt): String {
        val id = prompt.id.ifBlank { "new${nextId++}" }
        val saved = prompt.copy(id = id)
        items.value = items.value.filter { it.id != id } + saved
        return id
    }
    override suspend fun delete(id: String) { items.value = items.value.filter { it.id != id } }
}

class FakeChatEngine : ChatEngine {
    override val streaming: StateFlow<Map<String, StreamingText>> = MutableStateFlow(emptyMap())
    val generating = MutableStateFlow<Set<String>>(emptySet())
    override val generatingChats: StateFlow<Set<String>> = generating
    override val events: SharedFlow<EngineEvent> = MutableSharedFlow()
    val stopped = mutableListOf<String>()
    override fun send(chatId: String, text: String) = Unit
    override fun editAndResend(userMessageId: String, newText: String) = Unit
    override fun regenerate(assistantMessageId: String) = Unit
    override fun stop(chatId: String) { stopped += chatId }
}

/** Seules les méthodes utilisées par le tiroir sont implémentées. */
class FakeChatRepoForDrawer(initial: List<ChatSummary> = emptyList()) : ChatRepository {
    val summaries = MutableStateFlow(initial)
    val queries = mutableListOf<String>()
    val renamed = mutableListOf<Pair<String, String>>()
    val deleted = mutableListOf<String>()
    val forks = mutableListOf<Pair<String, String>>()
    var activePath: List<Message> = emptyList()

    override fun observeChatSummaries(query: String): Flow<List<ChatSummary>> {
        queries += query
        return summaries.map { list -> list.filter { it.title.contains(query, ignoreCase = true) } }
    }
    override suspend fun renameChat(chatId: String, title: String) { renamed += chatId to title }
    override suspend fun deleteChat(chatId: String) { deleted += chatId }
    override suspend fun getActivePath(chatId: String): List<Message> = activePath
    override suspend fun fork(chatId: String, uptoMessageId: String): String {
        forks += chatId to uptoMessageId
        return "fork-of-$chatId"
    }

    override fun observeChat(chatId: String): Flow<Chat?> = TODO()
    override fun observeThread(chatId: String) = TODO()
    override suspend fun getChat(chatId: String): Chat? = TODO()
    override suspend fun getMessage(messageId: String): Message? = TODO()
    override suspend fun createChat(modelId: String, systemPrompt: SystemPrompt?, params: GenParams): String = TODO()
    override suspend fun updateChatSettings(chatId: String, modelId: String?, params: GenParams?) = TODO()
    override suspend fun addUserMessage(chatId: String, parentId: String?, content: String): Message = TODO()
    override suspend fun addAssistantPlaceholder(chatId: String, parentId: String, modelId: String): Message = TODO()
    override suspend fun updateStreaming(messageId: String, content: String, reasoning: String?) = TODO()
    override suspend fun finishMessage(
        messageId: String, status: MessageStatus, finishReason: String?, error: String?,
        promptTokens: Int?, completionTokens: Int?, reasoningTokens: Int?,
    ) = TODO()
    override suspend fun editInPlace(messageId: String, content: String) = TODO()
    override suspend fun editUserAsBranch(messageId: String, newContent: String): Message = TODO()
    override suspend fun addAssistantSibling(messageId: String, modelId: String): Message = TODO()
    override suspend fun selectSibling(messageId: String) = TODO()
    override suspend fun deleteSubtree(messageId: String) = TODO()
    override suspend fun recoverInterrupted() = TODO()
    override suspend fun exportAll(): String = TODO()
    override suspend fun importAll(json: String, replace: Boolean) = TODO()
}

fun message(id: String, chatId: String = "c") = Message(
    id = id, chatId = chatId, parentId = null, role = Role.USER, content = "x", reasoning = null,
    selectedChildId = null, createdAt = 0, updatedAt = 0, modelId = null, status = MessageStatus.COMPLETE,
    finishReason = null, error = null, edited = false, promptTokens = null, completionTokens = null,
    reasoningTokens = null,
)
