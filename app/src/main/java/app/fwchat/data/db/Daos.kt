package app.fwchat.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import app.fwchat.domain.ChatSummary
import app.fwchat.domain.MessageStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Insert
    suspend fun insert(chat: ChatEntity)

    @Insert
    suspend fun insertAll(chats: List<ChatEntity>)

    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun get(id: String): ChatEntity?

    @Query("SELECT * FROM chats WHERE id = :id")
    fun observe(id: String): Flow<ChatEntity?>

    @Query("SELECT selectedRootId FROM chats WHERE id = :id")
    fun observeSelectedRootId(id: String): Flow<String?>

    /**
     * [normalizedQuery] est déjà normalisé (sans accents, minuscule) et échappé pour LIKE avec '!' comme
     * caractère d'échappement; vide = tout.
     */
    @Query(
        """
        SELECT c.id AS id, c.title AS title, c.modelId AS modelId, c.updatedAt AS updatedAt,
               (SELECT COUNT(*) FROM messages m WHERE m.chatId = c.id) AS messageCount
        FROM chats c
        WHERE :normalizedQuery = '' OR c.titleNormalized LIKE '%' || :normalizedQuery || '%' ESCAPE '!'
        ORDER BY c.updatedAt DESC, c.id ASC
        """,
    )
    fun observeSummaries(normalizedQuery: String): Flow<List<ChatSummary>>

    @Query("SELECT * FROM chats ORDER BY createdAt ASC, id ASC")
    suspend fun getAll(): List<ChatEntity>

    @Query("SELECT id FROM chats")
    suspend fun getAllIds(): List<String>

    @Query("UPDATE chats SET title = :title, titleNormalized = :normalized WHERE id = :id")
    suspend fun setTitle(id: String, title: String, normalized: String)

    @Query("UPDATE chats SET modelId = :modelId WHERE id = :id")
    suspend fun setModel(id: String, modelId: String)

    @Query("UPDATE chats SET paramsJson = :paramsJson WHERE id = :id")
    suspend fun setParams(id: String, paramsJson: String)

    @Query("UPDATE chats SET selectedRootId = :rootId WHERE id = :id")
    suspend fun setSelectedRoot(id: String, rootId: String?)

    @Query("UPDATE chats SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long)

    /** Les messages partent en cascade (clé étrangère). */
    @Query("DELETE FROM chats WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM chats")
    suspend fun deleteAll()
}

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(message: MessageEntity)

    @Insert
    suspend fun insertAll(messages: List<MessageEntity>)

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt ASC, id ASC")
    suspend fun getForChat(chatId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt ASC, id ASC")
    fun observeForChat(chatId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY chatId ASC, createdAt ASC, id ASC")
    suspend fun getAll(): List<MessageEntity>

    /** Checkpoint de streaming: ignoré si le message n'est plus en STREAMING (checkpoint tardif après la fin). */
    @Query(
        "UPDATE messages SET content = :content, reasoning = :reasoning, updatedAt = :now " +
            "WHERE id = :id AND status = 'STREAMING'",
    )
    suspend fun updateStreaming(id: String, content: String, reasoning: String?, now: Long): Int

    @Query(
        """
        UPDATE messages SET status = :status, finishReason = :finishReason, error = :error,
            promptTokens = :promptTokens, completionTokens = :completionTokens,
            reasoningTokens = :reasoningTokens, updatedAt = :now
        WHERE id = :id
        """,
    )
    suspend fun finish(
        id: String,
        status: MessageStatus,
        finishReason: String?,
        error: String?,
        promptTokens: Int?,
        completionTokens: Int?,
        reasoningTokens: Int?,
        now: Long,
    ): Int

    @Query("UPDATE messages SET content = :content, edited = 1, updatedAt = :now WHERE id = :id")
    suspend fun editContent(id: String, content: String, now: Long): Int

    @Query("UPDATE messages SET selectedChildId = :childId WHERE id = :id")
    suspend fun setSelectedChild(id: String, childId: String?)

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("UPDATE messages SET status = 'INTERRUPTED', updatedAt = :now WHERE status = 'STREAMING'")
    suspend fun markStreamingInterrupted(now: Long): Int
}

@Dao
interface SystemPromptDao {
    @Query("SELECT * FROM system_prompts ORDER BY name COLLATE NOCASE ASC, id ASC")
    fun observeAll(): Flow<List<SystemPromptEntity>>

    @Query("SELECT * FROM system_prompts ORDER BY name COLLATE NOCASE ASC, id ASC")
    suspend fun getAll(): List<SystemPromptEntity>

    @Query("SELECT * FROM system_prompts WHERE id = :id")
    suspend fun get(id: String): SystemPromptEntity?

    @Upsert
    suspend fun upsert(prompt: SystemPromptEntity)

    @Upsert
    suspend fun upsertAll(prompts: List<SystemPromptEntity>)

    @Query("DELETE FROM system_prompts WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM system_prompts")
    suspend fun deleteAll()

    @Query("SELECT id FROM system_prompts")
    suspend fun getAllIds(): List<String>
}
