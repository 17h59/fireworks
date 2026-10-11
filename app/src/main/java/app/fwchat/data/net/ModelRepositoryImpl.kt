package app.fwchat.data.net

import app.fwchat.data.DataJson
import app.fwchat.domain.FireworksException
import app.fwchat.domain.ModelInfo
import app.fwchat.domain.ModelRepository
import app.fwchat.domain.PlanInfo
import app.fwchat.domain.Provider
import app.fwchat.domain.ProviderApis
import app.fwchat.domain.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Cache disque de la liste des modèles d'un fournisseur. */
interface ModelCache {
    /** null = pas de cache (ou illisible). */
    suspend fun read(): List<ModelInfo>?
    suspend fun write(models: List<ModelInfo>)
    /** Instant (ms epoch) de la dernière écriture, null si inconnu. */
    suspend fun lastWriteMs(): Long? = null
}

private fun writeAtomically(file: File, text: String) {
    file.absoluteFile.parentFile?.mkdirs()
    val tmp = File(file.absolutePath + ".tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(file)) {
        file.writeText(tmp.readText())
        tmp.delete()
    }
}

/** Cache JSON Fireworks dans un fichier (typiquement filesDir/models_cache.json). */
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

    override suspend fun lastWriteMs(): Long? = withContext(Dispatchers.IO) {
        file.takeIf { it.isFile }?.lastModified()?.takeIf { it > 0 }
    }

    override suspend fun write(models: List<ModelInfo>) = withContext(Dispatchers.IO) {
        val payload = Payload(
            models = models.map { Entry(it.id, it.displayName, it.contextLength, it.supportsImageInput, it.supportsTools) },
        )
        writeAtomically(file, DataJson.encodeToString(Payload.serializer(), payload))
    }
}

/**
 * Cache COMPACT (~0,5 Mo pour ~7 500 modèles) pour Featherless: clés d'un caractère, valeurs par défaut omises,
 * pas de nom d'affichage (dérivé de l'id). Fichier distinct du cache Fireworks.
 */
class CompactModelCache(private val file: File) : ModelCache {

    @Serializable
    private class Entry(
        val i: String,
        /** Contexte (0 = inconnu, omis). */
        val c: Int = 0,
        /** Coût de concurrence (0 = inconnu, omis). */
        val k: Int = 0,
        /** Verrouillé. */
        val g: Boolean = false,
        /** Plan: 1 = oui, 0 = non, absent = inconnu. */
        val p: Int = -1,
        /** Vision. */
        val v: Boolean = false,
        /** Outils. */
        val t: Boolean = false,
    )

    @Serializable
    private class Payload(val version: Int = 1, val m: List<Entry> = emptyList())

    private val json = Json(from = DataJson) { encodeDefaults = false }

    override suspend fun read(): List<ModelInfo>? = withContext(Dispatchers.IO) {
        try {
            if (!file.isFile) return@withContext null
            json.decodeFromString(Payload.serializer(), file.readText()).m.map {
                ModelInfo(
                    id = it.i,
                    displayName = it.i.substringAfterLast('/'),
                    contextLength = it.c,
                    supportsImageInput = it.v,
                    supportsTools = it.t,
                    gated = it.g,
                    availableOnPlan = when (it.p) { 1 -> true; 0 -> false; else -> null },
                    concurrencyCost = it.k.takeIf { k -> k > 0 },
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun lastWriteMs(): Long? = withContext(Dispatchers.IO) {
        file.takeIf { it.isFile }?.lastModified()?.takeIf { it > 0 }
    }

    override suspend fun write(models: List<ModelInfo>) = withContext(Dispatchers.IO) {
        val payload = Payload(
            m = models.map {
                Entry(
                    i = it.id, c = it.contextLength, k = it.concurrencyCost ?: 0, g = it.gated,
                    p = when (it.availableOnPlan) { true -> 1; false -> 0; null -> -1 },
                    v = it.supportsImageInput, t = it.supportsTools,
                )
            },
        )
        writeAtomically(file, json.encodeToString(Payload.serializer(), payload))
    }
}

/** Cache disque du plan Featherless. */
interface PlanCache {
    suspend fun read(): PlanInfo?
    suspend fun write(plan: PlanInfo?)
}

class FilePlanCache(private val file: File) : PlanCache {
    @Serializable
    private class Payload(
        val name: String = "",
        val maxContextLength: Int? = null,
        val concurrency: Int? = null,
        val credits: Double? = null,
    )

    override suspend fun read(): PlanInfo? = withContext(Dispatchers.IO) {
        try {
            if (!file.isFile) return@withContext null
            val p = DataJson.decodeFromString(Payload.serializer(), file.readText())
            PlanInfo(p.name, p.maxContextLength, p.concurrency, p.credits)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun write(plan: PlanInfo?) = withContext(Dispatchers.IO) {
        if (plan == null) {
            file.delete()
            Unit
        } else {
            writeAtomically(
                file,
                DataJson.encodeToString(
                    Payload.serializer(), Payload(plan.name, plan.maxContextLength, plan.concurrency, plan.credits),
                ),
            )
        }
    }
}

/**
 * Tri d'affichage. Fireworks: par nom. Featherless: disponibles sur le plan d'abord (puis inconnu, puis hors plan),
 * puis par id. Coûteux sur ~7 500 entrées: à appeler hors du thread principal.
 */
internal fun sortModels(provider: Provider, list: List<ModelInfo>): List<ModelInfo> = when (provider) {
    Provider.FIREWORKS -> list.sortedWith(compareBy({ it.displayName.lowercase() }, { it.id }))
    Provider.FEATHERLESS -> {
        val keys = HashMap<String, String>(list.size * 2)
        list.sortedWith(
            compareBy<ModelInfo> { planRank(it.availableOnPlan) }
                .thenBy { keys.getOrPut(it.id) { it.id.lowercase() } }
                .thenBy { it.id },
        )
    }
}

private fun planRank(available: Boolean?): Int = when (available) { true -> 0; null -> 1; false -> 2 }

@OptIn(ExperimentalCoroutinesApi::class)
class ModelRepositoryImpl(
    private val apis: ProviderApis,
    private val settings: SettingsRepository,
    private val caches: Map<Provider, ModelCache>,
    private val planCache: PlanCache = object : PlanCache {
        override suspend fun read(): PlanInfo? = null
        override suspend fun write(plan: PlanInfo?) = Unit
    },
    private val clock: () -> Long = System::currentTimeMillis,
) : ModelRepository {

    /** null = pas encore chargé (cache disque pas encore lu). */
    private val states = Provider.entries.associateWith { MutableStateFlow<List<ModelInfo>?>(null) }
    private val locks = Provider.entries.associateWith { Mutex() }
    private val refreshedAt = ConcurrentHashMap<Provider, Long>()
    private val planState = MutableStateFlow<PlanState>(PlanState.Unloaded)

    private sealed interface PlanState {
        data object Unloaded : PlanState
        data class Loaded(val plan: PlanInfo?) : PlanState
    }

    override fun modelsFor(provider: Provider): Flow<List<ModelInfo>> {
        val state = states.getValue(provider)
        return flow {
            if (state.value == null) {
                // Lecture disque + tri hors du thread principal; n'écrase pas un refresh terminé entre-temps.
                val sorted = withContext(Dispatchers.Default) {
                    val cached = caches[provider]?.read()
                    if (cached == null) emptyList() else sortModels(provider, cached)
                }
                state.compareAndSet(null, sorted)
            }
            emitAll(state.filterNotNull())
        }
    }

    override val models: Flow<List<ModelInfo>> = settings.settings.map { it.activeProvider }.distinctUntilChanged()
        .flatMapLatest { modelsFor(it) }

    override val plan: Flow<PlanInfo?> = flow {
        if (planState.value == PlanState.Unloaded) {
            val cached = try { planCache.read() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            planState.compareAndSet(PlanState.Unloaded, PlanState.Loaded(cached))
        }
        emitAll(planState.map { (it as? PlanState.Loaded)?.plan })
    }.distinctUntilChanged()

    private suspend fun resolve(provider: Provider?): Provider =
        provider ?: settings.settings.first().activeProvider

    override suspend fun refresh(provider: Provider?): Result<Unit> {
        val target = try {
            resolve(provider)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(e)
        }
        return locks.getValue(target).withLock {
            try {
                val key = settings.apiKey(target)
                if (key.isNullOrBlank()) {
                    return@withLock Result.failure(FireworksException.Unauthorized("Clé API manquante"))
                }
                val api = apis[target]
                if (target == Provider.FEATHERLESS) {
                    // La liste est publique: seul /v1/plan valide vraiment la clé (401 si invalide).
                    try {
                        storePlan(api.plan(key))
                    } catch (e: FireworksException.Unauthorized) {
                        throw e
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Plan indisponible: la liste reste utilisable.
                    }
                }
                val list = withContext(Dispatchers.Default) { sortModels(target, api.listModels(key)) }
                states.getValue(target).value = list
                refreshedAt[target] = clock()
                try {
                    caches[target]?.write(list)
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

    override suspend fun refreshIfStale(provider: Provider, maxAgeMs: Long): Result<Unit> {
        try {
            if (settings.apiKey(provider).isNullOrBlank()) {
                return Result.failure(FireworksException.Unauthorized("Clé API manquante"))
            }
            val loaded = modelsFor(provider).first()
            val last = refreshedAt[provider] ?: caches[provider]?.lastWriteMs()
            if (loaded.isNotEmpty() && last != null && clock() - last in 0..maxAgeMs) return Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(e)
        }
        return refresh(provider)
    }

    override suspend fun refreshPlan(): Result<PlanInfo?> = try {
        val key = settings.apiKey(Provider.FEATHERLESS)
        if (key.isNullOrBlank()) {
            Result.failure(FireworksException.Unauthorized("Clé API manquante"))
        } else {
            val plan = apis[Provider.FEATHERLESS].plan(key)
            storePlan(plan)
            Result.success(plan)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private suspend fun storePlan(plan: PlanInfo?) {
        planState.value = PlanState.Loaded(plan)
        try {
            planCache.write(plan)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Sans effet.
        }
    }
}
