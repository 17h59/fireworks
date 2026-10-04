package app.fwchat.data.repo

import app.fwchat.data.db.AppDatabase
import app.fwchat.data.db.toDomain
import app.fwchat.data.db.toEntity
import app.fwchat.domain.SystemPrompt
import app.fwchat.domain.SystemPromptRepository
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SystemPromptRepositoryImpl(
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) : SystemPromptRepository {
    private val dao = db.systemPromptDao()

    /** Trié par nom (insensible à la casse). */
    override fun observeAll(): Flow<List<SystemPrompt>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun get(id: String): SystemPrompt? = dao.get(id)?.toDomain()

    override suspend fun upsert(prompt: SystemPrompt): String {
        val now = clock()
        val existing = if (prompt.id.isBlank()) null else dao.get(prompt.id)
        val id = prompt.id.ifBlank { idGenerator() }
        val createdAt = existing?.createdAt ?: prompt.createdAt.takeIf { it > 0 && prompt.id.isNotBlank() } ?: now
        dao.upsert(prompt.copy(id = id, createdAt = createdAt, updatedAt = now).toEntity())
        return id
    }

    /** Sans effet sur les chats: leur prompt est un snapshot. */
    override suspend fun delete(id: String) = dao.delete(id)
}
