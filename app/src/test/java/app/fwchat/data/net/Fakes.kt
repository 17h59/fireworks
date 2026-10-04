package app.fwchat.data.net

import app.fwchat.data.prefs.SecretStore
import app.fwchat.data.prefs.UnreadableSecretException
import app.fwchat.domain.AppDefaults
import app.fwchat.domain.AppSettings
import app.fwchat.domain.Chat
import app.fwchat.domain.ChatRepository
import app.fwchat.domain.ChatRequest
import app.fwchat.domain.ChatSummary
import app.fwchat.domain.FireworksApi
import app.fwchat.domain.GenParams
import app.fwchat.domain.Message
import app.fwchat.domain.MessageStatus
import app.fwchat.domain.ModelInfo
import app.fwchat.domain.ModelRepository
import app.fwchat.domain.Role
import app.fwchat.domain.SettingsRepository
import app.fwchat.domain.StreamEvent
import app.fwchat.domain.SystemPrompt
import app.fwchat.domain.ThreadItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

class InMemorySecretStore(var value: String? = null) : SecretStore {
    /** Simule un blob présent mais illisible (clé Keystore perdue). */
    var unreadable = false
    var writes = 0
    override suspend fun read(): String? {
        if (unreadable) throw UnreadableSecretException()
        return value
    }
    override suspend fun write(secret: String?) { writes++; value = secret; unreadable = false }
}

class FakeSettings(var key: String? = "fw_test_key") : SettingsRepository {
    override val settings: Flow<AppSettings> = flowOf(AppSettings(key != null, null, null, AppDefaults.GEN_PARAMS))
    override suspend fun apiKey(): String? = key
    override suspend fun setApiKey(key: String?) { this.key = key }
    override suspend fun setDefaultModel(modelId: String?) = Unit
    override suspend fun setDefaultSystemPrompt(promptId: String?) = Unit
    override suspend fun setDefaultParams(params: GenParams) = Unit
}

class FakeModelRepository : ModelRepository {
    var refreshCount = 0
    override val models: Flow<List<ModelInfo>> = MutableStateFlow(emptyList())
    override suspend fun refresh(): Result<Unit> { refreshCount++; return Result.success(Unit) }
}

class InMemoryModelCache(var stored: List<ModelInfo>? = null) : ModelCache {
    var writes = 0
    override suspend fun read(): List<ModelInfo>? = stored
    override suspend fun write(models: List<ModelInfo>) { stored = models; writes++ }
}

/** API scriptée: chaque appel de streamChat appelle [handler] et enregistre la requête. */
class FakeFireworksApi : FireworksApi {
    val requests = mutableListOf<ChatRequest>()
    var handler: (ChatRequest) -> Flow<StreamEvent> = { emptyFlow() }
    var models: List<ModelInfo> = emptyList()
    var listError: Throwable? = null
    var listCalls = 0

    override suspend fun listModels(apiKey: String): List<ModelInfo> {
        listCalls++
        listError?.let { throw it }
        return models
    }

    override fun streamChat(apiKey: String, request: ChatRequest): Flow<StreamEvent> {
        requests += request
        return handler(request)
    }
}

/** ChatRepository en mémoire: seule la partie utilisée par le moteur est implémentée. */
class FakeChatRepository : ChatRepository {
    val chats = LinkedHashMap<String, Chat>()
    val messages = LinkedHashMap<String, Message>()
    var updateStreamingCalls = 0
    private var seq = 0
    private var clock = 1000L

    private fun nextId(prefix: String) = "$prefix${++seq}"
    private fun now() = ++clock

    fun addChat(
        modelId: String = "accounts/fireworks/models/m",
        systemPrompt: String? = null,
        params: GenParams = GenParams(maxTokens = 100),
    ): String {
        val id = nextId("c")
        chats[id] = Chat(id, "", modelId, systemPrompt?.let { "p" }, systemPrompt, params, null, now(), now())
        return id
    }

    fun add(chatId: String, parent: String?, role: Role, content: String, status: MessageStatus = MessageStatus.COMPLETE): Message {
        val m = Message(
            id = nextId("m"), chatId = chatId, parentId = parent, role = role, content = content, reasoning = null,
            selectedChildId = null, createdAt = now(), updatedAt = now(), modelId = null, status = status,
            finishReason = null, error = null, edited = false, promptTokens = null, completionTokens = null,
            reasoningTokens = null,
        )
        messages[m.id] = m
        if (parent == null) {
            chats[chatId] = chats.getValue(chatId).copy(selectedRootId = m.id)
        } else {
            messages[parent] = messages.getValue(parent).copy(selectedChildId = m.id)
        }
        return m
    }

    fun msg(id: String) = messages.getValue(id)
    fun children(parentId: String?, chatId: String) =
        messages.values.filter { it.chatId == chatId && it.parentId == parentId }.sortedBy { it.createdAt }
    fun assistants(chatId: String) = messages.values.filter { it.chatId == chatId && it.role == Role.ASSISTANT }

    override fun observeChatSummaries(query: String): Flow<List<ChatSummary>> = emptyFlow()
    override fun observeChat(chatId: String): Flow<Chat?> = emptyFlow()
    override fun observeThread(chatId: String): Flow<List<ThreadItem>> = emptyFlow()

    override suspend fun getChat(chatId: String) = chats[chatId]
    override suspend fun getMessage(messageId: String) = messages[messageId]

    override suspend fun getActivePath(chatId: String): List<Message> {
        val chat = chats[chatId] ?: return emptyList()
        val out = ArrayList<Message>()
        var cur: Message? = (chat.selectedRootId ?: children(null, chatId).lastOrNull()?.id)?.let { messages[it] }
        while (cur != null) {
            out += cur
            val kids = children(cur.id, chatId)
            cur = (cur.selectedChildId ?: kids.lastOrNull()?.id)?.let { messages[it] }
        }
        return out
    }

    override suspend fun createChat(modelId: String, systemPrompt: SystemPrompt?, params: GenParams): String = TODO()
    override suspend fun renameChat(chatId: String, title: String) = TODO()
    override suspend fun deleteChat(chatId: String) = TODO()
    override suspend fun updateChatSettings(chatId: String, modelId: String?, params: GenParams?) {
        val c = chats.getValue(chatId)
        chats[chatId] = c.copy(modelId = modelId ?: c.modelId, params = params ?: c.params)
    }
    override suspend fun fork(chatId: String, uptoMessageId: String): String = TODO()

    override suspend fun addUserMessage(chatId: String, parentId: String?, content: String) =
        add(chatId, parentId, Role.USER, content)

    override suspend fun addAssistantPlaceholder(chatId: String, parentId: String, modelId: String) =
        add(chatId, parentId, Role.ASSISTANT, "", MessageStatus.STREAMING).let {
            messages[it.id] = it.copy(modelId = modelId); messages.getValue(it.id)
        }

    override suspend fun updateStreaming(messageId: String, content: String, reasoning: String?) {
        updateStreamingCalls++
        messages[messageId] = messages.getValue(messageId).copy(content = content, reasoning = reasoning)
    }

    override suspend fun finishMessage(
        messageId: String, status: MessageStatus, finishReason: String?, error: String?,
        promptTokens: Int?, completionTokens: Int?, reasoningTokens: Int?,
    ) {
        messages[messageId] = messages.getValue(messageId).copy(
            status = status, finishReason = finishReason, error = error,
            promptTokens = promptTokens, completionTokens = completionTokens, reasoningTokens = reasoningTokens,
        )
    }

    override suspend fun editInPlace(messageId: String, content: String) = TODO()

    override suspend fun editUserAsBranch(messageId: String, newContent: String): Message {
        val old = messages.getValue(messageId)
        return add(old.chatId, old.parentId, Role.USER, newContent)
    }

    override suspend fun addAssistantSibling(messageId: String, modelId: String): Message {
        val old = messages.getValue(messageId)
        return addAssistantPlaceholder(old.chatId, old.parentId!!, modelId)
    }

    override suspend fun selectSibling(messageId: String) = TODO()
    override suspend fun deleteSubtree(messageId: String) = TODO()
    override suspend fun recoverInterrupted() = TODO()
    override suspend fun exportAll(): String = TODO()
    override suspend fun importAll(json: String, replace: Boolean) = TODO()
}
