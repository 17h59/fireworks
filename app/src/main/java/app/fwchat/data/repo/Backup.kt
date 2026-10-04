package app.fwchat.data.repo

import app.fwchat.domain.GenParams
import kotlinx.serialization.Serializable

/** Format de sauvegarde JSON versionné. Champs inconnus ignorés à l'import (compatibilité ascendante). */
@Serializable
internal data class BackupFile(
    val format: String = "",
    val version: Int = 0,
    val exportedAt: Long = 0,
    val systemPrompts: List<BackupPrompt> = emptyList(),
    val chats: List<BackupChat> = emptyList(),
) {
    companion object {
        const val FORMAT = "fwchat-backup"
        const val VERSION = 1
    }
}

@Serializable
internal data class BackupPrompt(
    val id: String,
    val name: String,
    val text: String,
    val family: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

@Serializable
internal data class BackupChat(
    val id: String,
    val title: String = "",
    val modelId: String,
    val systemPromptName: String? = null,
    val systemPromptText: String? = null,
    val params: GenParams = GenParams(),
    val selectedRootId: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val messages: List<BackupMessage> = emptyList(),
)

@Serializable
internal data class BackupMessage(
    val id: String,
    val parentId: String? = null,
    val role: String,
    val content: String = "",
    val reasoning: String? = null,
    val selectedChildId: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val modelId: String? = null,
    val status: String = "COMPLETE",
    val finishReason: String? = null,
    val error: String? = null,
    val edited: Boolean = false,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val reasoningTokens: Int? = null,
)
