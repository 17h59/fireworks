package app.fwchat.ui.chat

import app.fwchat.data.repo.ThreadLogic
import app.fwchat.domain.Chat
import app.fwchat.domain.ChatEngine
import app.fwchat.domain.ChatRepository
import app.fwchat.domain.ChatSummary
import app.fwchat.domain.EngineEvent
import app.fwchat.domain.GenParams
import app.fwchat.domain.Message
import app.fwchat.domain.MessageStatus
import app.fwchat.domain.Role
import app.fwchat.domain.StreamingText
import app.fwchat.domain.SystemPrompt
import app.fwchat.domain.ThreadItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

/** ChatRepository en mémoire (arbre réel via ThreadLogic). [log] est partagé avec [RecordingEngine] pour vérifier l'ordre des appels. */
class MemChatRepo(val log: MutableList<String> = mutableListOf()) : ChatRepository {
    val chats = LinkedHashMap<String, Chat>()
    val msgs = ArrayList<Message>()
    private val tick = MutableStateFlow(0)
    private var seq = 0
    private var clock = 1000L

    data class Created(val modelId: String, val prompt: SystemPrompt?, val params: GenParams)
    data class SettingsCall(val chatId: String, val modelId: String?, val params: GenParams?)

    val created = mutableListOf<Created>()
    val settingsCalls = mutableListOf<SettingsCall>()
    val inPlaceEdits = mutableListOf<Pair<String, String>>()
    val branchEdits = mutableListOf<Pair<String, String>>()
    val forks = mutableListOf<Pair<String, String>>()
    val deletedSubtrees = mutableListOf<String>()
    val renames = mutableListOf<Pair<String, String>>()

    private fun changed() { tick.value = tick.value + 1 }
    private fun now() = ++clock

    fun addMessage(chatId: String, parent: String?, role: Role, content: String, status: MessageStatus = MessageStatus.COMPLETE): Message {
        val m = Message(
            id = "m${++seq}", chatId = chatId, parentId = parent, role = role, content = content, reasoning = null,
            selectedChildId = null, createdAt = now(), updatedAt = now(), modelId = null, status = status,
            finishReason = null, error = null, edited = false, promptTokens = null, completionTokens = null,
            reasoningTokens = null,
        )
        msgs += m
        select(m)
        changed()
        return m
    }

    private fun select(m: Message) {
        if (m.parentId == null) {
            chats[m.chatId] = chats.getValue(m.chatId).copy(selectedRootId = m.id)
        } else {
            replace(m.parentId) { it.copy(selectedChildId = m.id) }
        }
    }

    private fun replace(id: String, f: (Message) -> Message) {
        val i = msgs.indexOfFirst { it.id == id }
        if (i >= 0) msgs[i] = f(msgs[i])
    }

    override fun observeChatSummaries(query: String): Flow<List<ChatSummary>> = emptyFlow()
    override fun observeChat(chatId: String): Flow<Chat?> = tick.map { chats[chatId] }
    override fun observeThread(chatId: String): Flow<List<ThreadItem>> = tick.map {
        ThreadLogic.activeThread(msgs.filter { it.chatId == chatId }, chats[chatId]?.selectedRootId)
    }

    override suspend fun getChat(chatId: String): Chat? = chats[chatId]
    override suspend fun getActivePath(chatId: String): List<Message> =
        ThreadLogic.activePath(msgs.filter { it.chatId == chatId }, chats[chatId]?.selectedRootId)
    override suspend fun getMessage(messageId: String): Message? = msgs.firstOrNull { it.id == messageId }

    override suspend fun createChat(modelId: String, systemPrompt: SystemPrompt?, params: GenParams): String {
        log += "create"
        created += Created(modelId, systemPrompt, params)
        val id = "c${++seq}"
        chats[id] = Chat(id, "", modelId, systemPrompt?.name, systemPrompt?.text, params, null, now(), now())
        changed()
        return id
    }

    override suspend fun renameChat(chatId: String, title: String) {
        renames += chatId to title
        chats[chatId] = chats.getValue(chatId).copy(title = title)
        changed()
    }

    override suspend fun deleteChat(chatId: String) = Unit

    override suspend fun updateChatSettings(chatId: String, modelId: String?, params: GenParams?) {
        settingsCalls += SettingsCall(chatId, modelId, params)
        val c = chats.getValue(chatId)
        chats[chatId] = c.copy(modelId = modelId ?: c.modelId, params = params ?: c.params)
        changed()
    }

    override suspend fun fork(chatId: String, uptoMessageId: String): String {
        forks += chatId to uptoMessageId
        return "fork-of-$chatId"
    }

    override suspend fun addUserMessage(chatId: String, parentId: String?, content: String): Message =
        addMessage(chatId, parentId, Role.USER, content)

    override suspend fun addAssistantPlaceholder(chatId: String, parentId: String, modelId: String): Message =
        addMessage(chatId, parentId, Role.ASSISTANT, "", MessageStatus.STREAMING)

    override suspend fun updateStreaming(messageId: String, content: String, reasoning: String?) = Unit
    override suspend fun finishMessage(
        messageId: String, status: MessageStatus, finishReason: String?, error: String?,
        promptTokens: Int?, completionTokens: Int?, reasoningTokens: Int?,
    ) = Unit

    override suspend fun editInPlace(messageId: String, content: String) {
        log += "editInPlace"
        inPlaceEdits += messageId to content
        replace(messageId) { it.copy(content = content, edited = true) }
        changed()
    }

    override suspend fun editUserAsBranch(messageId: String, newContent: String): Message {
        branchEdits += messageId to newContent
        val old = getMessage(messageId)!!
        return addMessage(old.chatId, old.parentId, Role.USER, newContent)
    }

    override suspend fun addAssistantSibling(messageId: String, modelId: String): Message {
        val old = getMessage(messageId)!!
        return addMessage(old.chatId, old.parentId, Role.ASSISTANT, "", MessageStatus.STREAMING)
    }

    override suspend fun selectSibling(messageId: String) {
        val m = getMessage(messageId)!!
        select(m)
        changed()
    }

    override suspend fun deleteSubtree(messageId: String) {
        deletedSubtrees += messageId
        val ids = ThreadLogic.subtreeIds(msgs, messageId)
        msgs.removeAll { it.id in ids }
        changed()
    }

    override suspend fun recoverInterrupted() = Unit
    override suspend fun exportAll(): String = ""
    override suspend fun importAll(json: String, replace: Boolean) = Unit
}

class RecordingEngine(private val log: MutableList<String> = mutableListOf()) : ChatEngine {
    override val streaming: MutableStateFlow<Map<String, StreamingText>> = MutableStateFlow(emptyMap())
    val generating = MutableStateFlow<Set<String>>(emptySet())
    override val generatingChats: StateFlow<Set<String>> = generating
    override val events: MutableSharedFlow<EngineEvent> = MutableSharedFlow(extraBufferCapacity = 8)

    val sent = mutableListOf<Pair<String, String>>()
    val editResends = mutableListOf<Pair<String, String>>()
    val regenerated = mutableListOf<String>()
    val stopped = mutableListOf<String>()

    override fun send(chatId: String, text: String) { log += "send"; sent += chatId to text }
    override fun editAndResend(userMessageId: String, newText: String) { editResends += userMessageId to newText }
    override fun regenerate(assistantMessageId: String) { regenerated += assistantMessageId }
    override fun stop(chatId: String) { stopped += chatId }
}
