package app.fwchat.data.net

import app.fwchat.data.DataJson
import app.fwchat.domain.FireworksApi
import app.fwchat.domain.FireworksException
import app.fwchat.domain.ModelInfo
import app.fwchat.domain.ModelRepository
import app.fwchat.domain.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/** Cache disque de la liste des modèles. */
interface ModelCache {
    /** null = pas de cache (ou illisible). */
    suspend fun read(): List<ModelInfo>?
    suspend fun write(models: List<ModelInfo>)
}

/** Cache JSON dans un fichier (typiquement filesDir/models_cache.json). */
class FileModelCache(private val file: File) : ModelCache {

    @Serializable
    private data class Entry(
        val id: String,
        val displayName: String,
        val contextLength: Int,
        val supportsImageInput: Boolean,
        val supportsTools: Boolean,
    )

    @Serializable
    private data class Payload(val version: Int = 1, val models: List<Entry> = emptyList())

    override suspend fun read(): List<ModelInfo>? = withContext(Dispatchers.IO) {
        try {
            if (!file.isFile) return@withContext null
            DataJson.decodeFromString(Payload.serializer(), file.readText()).models.map {
                ModelInfo(it.id, it.displayName, it.contextLength, it.supportsImageInput, it.supportsTools)
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun write(models: List<ModelInfo>) = withContext(Dispatchers.IO) {
        file.absoluteFile.parentFile?.mkdirs()
        val payload = Payload(
            models = models.map { Entry(it.id, it.displayName, it.contextLength, it.supportsImageInput, it.supportsTools) },
        )
        val tmp = File(file.absolutePath + ".tmp")
        tmp.writeText(DataJson.encodeToString(Payload.serializer(), payload))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
        Unit
    }
}

class ModelRepositoryImpl(
    private val api: FireworksApi,
    private val settings: SettingsRepository,
    private val cache: ModelCache,
) : ModelRepository {

    /** null = pas encore chargé (cache disque pas encore lu). */
    private val state = MutableStateFlow<List<ModelInfo>?>(null)
    private val refreshLock = Mutex()

    override val models: Flow<List<ModelInfo>> = flow {
        if (state.value == null) {
            val cached = cache.read()
            // N'écrase pas un refresh qui aurait fini entre-temps.
            state.compareAndSet(null, cached?.sortedBy { it.displayName.lowercase() } ?: emptyList())
        }
        emitAll(state.filterNotNull())
    }

    override suspend fun refresh(): Result<Unit> = refreshLock.withLock {
        try {
            val key = settings.apiKey()
            if (key.isNullOrBlank()) {
                return@withLock Result.failure(FireworksException.Unauthorized("Clé API manquante"))
            }
            val list = api.listModels(key).sortedWith(compareBy({ it.displayName.lowercase() }, { it.id }))
            state.value = list
            try {
                cache.write(list)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Un cache non inscriptible n'est pas une erreur de rafraîchissement.
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
