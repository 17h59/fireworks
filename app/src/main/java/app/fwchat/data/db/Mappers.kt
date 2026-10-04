package app.fwchat.data.db

import app.fwchat.data.repo.ThreadLogic
import app.fwchat.domain.Chat
import app.fwchat.domain.GenParams
import app.fwchat.domain.Message
import app.fwchat.domain.SystemPrompt
import kotlinx.serialization.json.Json

/** JSON partagé par la base (params) et la sauvegarde. */
internal val DataJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal fun encodeParams(params: GenParams): String = DataJson.encodeToString(GenParams.serializer(), params)

internal fun decodeParams(json: String): GenParams =
    try {
        DataJson.decodeFromString(GenParams.serializer(), json)
    } catch (_: Exception) {
        GenParams()
    }

internal fun Chat.toEntity() = ChatEntity(
    id = id,
    title = title,
    titleNormalized = ThreadLogic.normalize(title),
    modelId = modelId,
    systemPromptName = systemPromptName,
    systemPromptText = systemPromptText,
    paramsJson = encodeParams(params),
    selectedRootId = selectedRootId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun ChatEntity.toDomain() = Chat(
    id = id,
    title = title,
    modelId = modelId,
    systemPromptName = systemPromptName,
    systemPromptText = systemPromptText,
    params = decodeParams(paramsJson),
    selectedRootId = selectedRootId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun Message.toEntity() = MessageEntity(
    id = id,
    chatId = chatId,
    parentId = parentId,
    role = role,
    content = content,
    reasoning = reasoning,
    selectedChildId = selectedChildId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    modelId = modelId,
    status = status,
    finishReason = finishReason,
    error = error,
    edited = edited,
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    reasoningTokens = reasoningTokens,
)

internal fun MessageEntity.toDomain() = Message(
    id = id,
    chatId = chatId,
    parentId = parentId,
    role = role,
    content = content,
    reasoning = reasoning,
    selectedChildId = selectedChildId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    modelId = modelId,
    status = status,
    finishReason = finishReason,
    error = error,
    edited = edited,
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    reasoningTokens = reasoningTokens,
)

internal fun SystemPrompt.toEntity() = SystemPromptEntity(id, name, text, family, createdAt, updatedAt)

internal fun SystemPromptEntity.toDomain() = SystemPrompt(id, name, text, family, createdAt, updatedAt)
