package app.fwchat.data.repo

import androidx.room.withTransaction
import app.fwchat.data.db.AppDatabase
import app.fwchat.data.db.ChatEntity
import app.fwchat.data.DataJson
import app.fwchat.data.db.MessageEntity
import app.fwchat.data.db.SystemPromptEntity
import app.fwchat.data.db.decodeParams
import app.fwchat.data.encodeParams
import app.fwchat.data.db.toDomain
import app.fwchat.data.db.toEntity
import app.fwchat.domain.Chat
import app.fwchat.domain.ChatRepository
import app.fwchat.domain.ChatSummary
import app.fwchat.domain.GenParams
import app.fwchat.domain.Message
import app.fwchat.domain.MessageStatus
import app.fwchat.domain.PromptFamily
import app.fwchat.domain.Role
import app.fwchat.domain.SystemPrompt
import app.fwchat.domain.ThreadItem
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException

/**
 * Implémentation Room de [ChatRepository]. Sémantique des branches: docs/ARCHITECTURE.md.
 *
 * Les horodatages sont strictement croissants par instance (même si [clock] renvoie deux fois la même valeur),
 * ce qui rend déterministes "enfant le plus récent" et le tri des frères.
 */
class ChatRepositoryImpl(
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ChatRepository {
    private val chats = db.chatDao()
    private val messages = db.messageDao()
    private val prompts = db.systemPromptDao()
    private val lastTimestamp = AtomicLong(Long.MIN_VALUE)

    private fun now(): Long = lastTimestamp.updateAndGet { prev -> maxOf(clock(), prev + 1) }

    // ---------------------------------------------------------------- lecture

    override fun observeChatSummaries(query: String): Flow<List<ChatSummary>> =
        chats.observeSummaries(ThreadLogic.searchPattern(query)).distinctUntilChanged()

    override fun observeChat(chatId: String): Flow<Chat?> =
        chats.observe(chatId).map { it?.toDomain() }.distinctUntilChanged()

    override fun observeThread(chatId: String): Flow<List<ThreadItem>> =
        combine(
            messages.observeForChat(chatId),
            chats.observeSelectedRootId(chatId).distinctUntilChanged(),
        ) { entities, rootId ->
            ThreadLogic.activeThread(entities.map { it.toDomain() }, rootId)
        }
            .distinctUntilChanged()
            .flowOn(defaultDispatcher)

    override suspend fun getChat(chatId: String): Chat? = chats.get(chatId)?.toDomain()

    override suspend fun getActivePath(chatId: String): List<Message> {
        val rootId = chats.get(chatId)?.selectedRootId
        return ThreadLogic.activePath(messages.getForChat(chatId).map { it.toDomain() }, rootId)
    }

    override suspend fun getMessage(messageId: String): Message? = messages.get(messageId)?.toDomain()

    // ---------------------------------------------------------------- chats

    override suspend fun createChat(modelId: String, systemPrompt: SystemPrompt?, params: GenParams): String {
        val t = now()
        val chat = Chat(
            id = idGenerator(),
            title = "",
            modelId = modelId,
            systemPromptName = systemPrompt?.name,
            systemPromptText = systemPrompt?.text,
            params = params,
            selectedRootId = null,
            createdAt = t,
            updatedAt = t,
        )
        chats.insert(chat.toEntity())
        return chat.id
    }

    override suspend fun renameChat(chatId: String, title: String) {
        val t = title.trim()
        chats.setTitle(chatId, t, ThreadLogic.normalize(t))
    }

    override suspend fun deleteChat(chatId: String) = chats.delete(chatId)

    override suspend fun updateChatSettings(chatId: String, modelId: String?, params: GenParams?) {
        db.withTransaction {
            if (modelId != null) chats.setModel(chatId, modelId)
            if (params != null) chats.setParams(chatId, encodeParams(params))
        }
    }

    override suspend fun fork(chatId: String, uptoMessageId: String): String = db.withTransaction {
        val chat = chats.get(chatId)?.toDomain() ?: throw IllegalArgumentException("Chat introuvable: $chatId")
        val all = messages.getForChat(chatId).map { it.toDomain() }
        val chain = ThreadLogic.ancestorChain(all, uptoMessageId)
        require(chain.isNotEmpty()) { "Message introuvable dans ce chat: $uptoMessageId" }
        val newChatId = idGenerator()
        val clones = ThreadLogic.cloneChain(chain, newChatId, idGenerator)
        val t = now()
        val newChat = chat.copy(
            id = newChatId,
            title = ThreadLogic.forkTitle(chat.title),
            selectedRootId = clones.first().id,
            createdAt = t,
            updatedAt = t,
        )
        chats.insert(newChat.toEntity())
        messages.insertAll(clones.map { it.toEntity() })
        newChatId
    }

    // ---------------------------------------------------------------- messages

    override suspend fun addUserMessage(chatId: String, parentId: String?, content: String): Message =
        db.withTransaction {
            val chat = chats.get(chatId) ?: throw IllegalArgumentException("Chat introuvable: $chatId")
            requireParentInChat(chatId, parentId)
            val msg = newMessage(chatId, parentId, Role.USER, content, modelId = null, status = MessageStatus.COMPLETE)
            insertAndSelect(msg)
            if (chat.title.isEmpty()) {
                val title = ThreadLogic.titleFrom(content)
                if (title.isNotEmpty()) chats.setTitle(chatId, title, ThreadLogic.normalize(title))
            }
            msg
        }

    override suspend fun addAssistantPlaceholder(chatId: String, parentId: String, modelId: String): Message =
        db.withTransaction {
            requireNotNull(chats.get(chatId)) { "Chat introuvable: $chatId" }
            requireParentInChat(chatId, parentId)
            val msg = newMessage(chatId, parentId, Role.ASSISTANT, "", modelId, MessageStatus.STREAMING)
            insertAndSelect(msg)
            msg
        }

    override suspend fun updateStreaming(messageId: String, content: String, reasoning: String?) {
        messages.updateStreaming(messageId, content, reasoning, now())
    }

    override suspend fun finishMessage(
        messageId: String,
        status: MessageStatus,
        finishReason: String?,
        error: String?,
        promptTokens: Int?,
        completionTokens: Int?,
        reasoningTokens: Int?,
    ) {
        db.withTransaction {
            val t = now()
            val changed = messages.finish(
                messageId, status, finishReason, error, promptTokens, completionTokens, reasoningTokens, t,
            )
            if (changed > 0) messages.get(messageId)?.let { chats.touch(it.chatId, t) }
        }
    }

    override suspend fun editInPlace(messageId: String, content: String) {
        db.withTransaction {
            val t = now()
            if (messages.editContent(messageId, content, t) > 0) {
                messages.get(messageId)?.let { chats.touch(it.chatId, t) }
            }
        }
    }

    override suspend fun editUserAsBranch(messageId: String, newContent: String): Message = db.withTransaction {
        val original = requireMessage(messageId)
        require(original.role == Role.USER) { "Seul un message utilisateur peut être édité en branche" }
        val msg = newMessage(original.chatId, original.parentId, Role.USER, newContent, null, MessageStatus.COMPLETE)
        insertAndSelect(msg)
        msg
    }

    override suspend fun addAssistantSibling(messageId: String, modelId: String): Message = db.withTransaction {
        val original = requireMessage(messageId)
        require(original.role == Role.ASSISTANT) { "Seul un message assistant peut être régénéré" }
        val msg = newMessage(original.chatId, original.parentId, Role.ASSISTANT, "", modelId, MessageStatus.STREAMING)
        insertAndSelect(msg)
        msg
    }

    override suspend fun selectSibling(messageId: String) {
        db.withTransaction {
            val m = messages.get(messageId) ?: return@withTransaction
            select(m.chatId, m.parentId, m.id)
        }
    }

    override suspend fun deleteSubtree(messageId: String) {
        db.withTransaction {
            val target = messages.get(messageId) ?: return@withTransaction
            val all = messages.getForChat(target.chatId).map { it.toDomain() }
            val ids = ThreadLogic.subtreeIds(all, messageId)
            val siblings = ThreadLogic.childrenByParent(all)[target.parentId].orEmpty()
            val fallback = ThreadLogic.fallbackSibling(siblings, messageId)

            ids.chunked(500).forEach { messages.deleteByIds(it) }

            if (target.parentId == null) {
                if (chats.get(target.chatId)?.selectedRootId == messageId) {
                    chats.setSelectedRoot(target.chatId, fallback?.id)
                }
            } else {
                val parent = messages.get(target.parentId)
                if (parent != null && parent.selectedChildId == messageId) {
                    messages.setSelectedChild(parent.id, fallback?.id)
                }
            }
            chats.touch(target.chatId, now())
        }
    }

    override suspend fun recoverInterrupted() {
        messages.markStreamingInterrupted(now())
    }

    // ---------------------------------------------------------------- sauvegarde

    override suspend fun exportAll(): String = db.withTransaction {
        val byChat = messages.getAll().groupBy { it.chatId }
        val file = BackupFile(
            format = BackupFile.FORMAT,
            version = BackupFile.VERSION,
            exportedAt = clock(),
            systemPrompts = prompts.getAll().map {
                BackupPrompt(it.id, it.name, it.text, it.family?.name, it.createdAt, it.updatedAt)
            },
            chats = chats.getAll().map { c ->
                BackupChat(
                    id = c.id,
                    title = c.title,
                    modelId = c.modelId,
                    systemPromptName = c.systemPromptName,
                    systemPromptText = c.systemPromptText,
                    params = decodeParams(c.paramsJson),
                    selectedRootId = c.selectedRootId,
                    createdAt = c.createdAt,
                    updatedAt = c.updatedAt,
                    messages = byChat[c.id].orEmpty().map { it.toBackup() },
                )
            },
        )
        DataJson.encodeToString(BackupFile.serializer(), file)
    }

    /**
     * [replace] = true: efface tous les chats et prompts avant d'importer. false: fusion, les chats et prompts dont l'id
     * existe déjà sont conservés tels quels (ceux du fichier sont ignorés). Lève [IllegalArgumentException] si le fichier
     * est invalide ou d'une version plus récente; dans ce cas rien n'est modifié.
     */
    override suspend fun importAll(json: String, replace: Boolean) {
        val file = parseBackup(json)
        db.withTransaction {
            if (replace) {
                chats.deleteAll()
                prompts.deleteAll()
            }
            val existingChats = if (replace) emptySet() else chats.getAllIds().toSet()
            val existingPrompts = if (replace) emptySet() else prompts.getAllIds().toSet()

            val newPrompts = file.systemPrompts.filter { it.id !in existingPrompts }.map { it.toEntity() }
            if (newPrompts.isNotEmpty()) prompts.upsertAll(newPrompts)

            for (bc in file.chats) {
                if (bc.id in existingChats) continue
                val msgs = bc.messages.mapNotNull { it.toEntityOrNull(bc.id) }
                val ids = msgs.mapTo(HashSet()) { it.id }
                val roots = msgs.filter { it.parentId == null }.mapTo(HashSet()) { it.id }
                chats.insert(
                    ChatEntity(
                        id = bc.id,
                        title = bc.title,
                        titleNormalized = ThreadLogic.normalize(bc.title),
                        modelId = bc.modelId,
                        systemPromptName = bc.systemPromptName,
                        systemPromptText = bc.systemPromptText,
                        paramsJson = encodeParams(bc.params),
                        selectedRootId = bc.selectedRootId?.takeIf { it in roots },
                        createdAt = bc.createdAt,
                        updatedAt = bc.updatedAt,
                    ),
                )
                msgs.chunked(200).forEach { chunk ->
                    messages.insertAll(chunk.map { m -> m.copy(selectedChildId = m.selectedChildId?.takeIf { it in ids }) })
                }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun parseBackup(json: String): BackupFile {
        val file = try {
            DataJson.decodeFromString(BackupFile.serializer(), json)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("Fichier de sauvegarde invalide", e)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Fichier de sauvegarde invalide", e)
        }
        require(file.format == BackupFile.FORMAT) { "Ce fichier n'est pas une sauvegarde FW Chat" }
        require(file.version in 1..BackupFile.VERSION) { "Version de sauvegarde non supportée: ${file.version}" }
        return file
    }

    private suspend fun requireMessage(id: String): MessageEntity =
        messages.get(id) ?: throw IllegalArgumentException("Message introuvable: $id")

    private suspend fun requireParentInChat(chatId: String, parentId: String?) {
        if (parentId == null) return
        val parent = messages.get(parentId) ?: throw IllegalArgumentException("Message parent introuvable: $parentId")
        require(parent.chatId == chatId) { "Le parent appartient à un autre chat" }
    }

    private fun newMessage(
        chatId: String,
        parentId: String?,
        role: Role,
        content: String,
        modelId: String?,
        status: MessageStatus,
    ): Message {
        val t = now()
        return Message(
            id = idGenerator(),
            chatId = chatId,
            parentId = parentId,
            role = role,
            content = content,
            reasoning = null,
            selectedChildId = null,
            createdAt = t,
            updatedAt = t,
            modelId = modelId,
            status = status,
            finishReason = null,
            error = null,
            edited = false,
            promptTokens = null,
            completionTokens = null,
            reasoningTokens = null,
        )
    }

    private suspend fun insertAndSelect(m: Message) {
        messages.insert(m.toEntity())
        select(m.chatId, m.parentId, m.id)
        chats.touch(m.chatId, m.createdAt)
    }

    private suspend fun select(chatId: String, parentId: String?, messageId: String) {
        if (parentId == null) chats.setSelectedRoot(chatId, messageId) else messages.setSelectedChild(parentId, messageId)
    }
}

private fun MessageEntity.toBackup() = BackupMessage(
    id = id,
    parentId = parentId,
    role = role.name,
    content = content,
    reasoning = reasoning,
    selectedChildId = selectedChildId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    modelId = modelId,
    status = status.name,
    finishReason = finishReason,
    error = error,
    edited = edited,
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    reasoningTokens = reasoningTokens,
)

private fun BackupMessage.toEntityOrNull(chatId: String): MessageEntity? {
    val r = Role.entries.firstOrNull { it.name == role } ?: return null
    val s = MessageStatus.entries.firstOrNull { it.name == status } ?: MessageStatus.COMPLETE
    return MessageEntity(
        id = id,
        chatId = chatId,
        parentId = parentId,
        role = r,
        content = content,
        reasoning = reasoning,
        selectedChildId = selectedChildId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        modelId = modelId,
        status = if (s == MessageStatus.STREAMING) MessageStatus.INTERRUPTED else s,
        finishReason = finishReason,
        error = error,
        edited = edited,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        reasoningTokens = reasoningTokens,
    )
}

private fun BackupPrompt.toEntity() = SystemPromptEntity(
    id = id,
    name = name,
    text = text,
    family = family?.let { f -> PromptFamily.entries.firstOrNull { it.name == f } },
    createdAt = createdAt,
    updatedAt = updatedAt,
)
