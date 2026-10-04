package app.fwchat.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.fwchat.domain.MessageStatus
import app.fwchat.domain.PromptFamily
import app.fwchat.domain.Role

@Entity(tableName = "chats", indices = [Index("updatedAt")])
data class ChatEntity(
    @PrimaryKey val id: String,
    val title: String,
    /** Titre sans accents ni casse, maintenu à l'écriture; sert à la recherche (LIKE). */
    val titleNormalized: String,
    val modelId: String,
    val systemPromptName: String?,
    val systemPromptText: String?,
    /** GenParams sérialisé en JSON. */
    val paramsJson: String,
    val selectedRootId: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("chatId", "createdAt")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val chatId: String,
    /** Pas de clé étrangère: l'arbre est géré par la couche repo (suppression de sous-arbre explicite). */
    val parentId: String?,
    val role: Role,
    val content: String,
    val reasoning: String?,
    val selectedChildId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val modelId: String?,
    val status: MessageStatus,
    val finishReason: String?,
    val error: String?,
    val edited: Boolean,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val reasoningTokens: Int?,
)

@Entity(tableName = "system_prompts")
data class SystemPromptEntity(
    @PrimaryKey val id: String,
    val name: String,
    val text: String,
    val family: PromptFamily?,
    val createdAt: Long,
    val updatedAt: Long,
)
