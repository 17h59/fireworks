package app.fwchat

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.fwchat.data.db.AppDatabase
import app.fwchat.data.db.build
import app.fwchat.data.net.ChatEngineImpl
import app.fwchat.data.net.FileModelCache
import app.fwchat.data.net.FireworksApiImpl
import app.fwchat.data.net.ModelRepositoryImpl
import app.fwchat.data.prefs.KeystoreSecretStore
import app.fwchat.data.prefs.SettingsRepositoryImpl
import app.fwchat.data.repo.ChatRepositoryImpl
import app.fwchat.data.repo.SystemPromptRepositoryImpl
import app.fwchat.domain.ChatEngine
import app.fwchat.domain.ChatRepository
import app.fwchat.domain.ModelRepository
import app.fwchat.domain.SettingsRepository
import app.fwchat.domain.SystemPromptRepository
import app.fwchat.ui.shell.applyDefaultModelIfNeeded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Conteneur de dépendances manuel (pas de Hilt). Room, Keystore et DataStore n'ouvrent rien avant
 * leur premier accès: [FwChatApp.onCreate] ne bloque pas le thread principal.
 */
class AppContainer(context: Context) {

    /** Scope applicatif: la génération et le rafraîchissement des modèles survivent à la navigation. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val appContext = context.applicationContext
    private val filesDir: File = appContext.filesDir

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val api = FireworksApiImpl(httpClient, baseUrl = "https://api.fireworks.ai")

    private val database: AppDatabase = AppDatabase.build(appContext)

    private val dataStore = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        produceFile = { File(filesDir, "datastore/settings.preferences_pb") },
    )

    val chats: ChatRepository = ChatRepositoryImpl(database)
    val prompts: SystemPromptRepository = SystemPromptRepositoryImpl(database)
    val settings: SettingsRepository = SettingsRepositoryImpl(
        dataStore = dataStore,
        secretStore = KeystoreSecretStore(File(filesDir, "secrets/api_key.bin")),
    )
    val models: ModelRepository = ModelRepositoryImpl(
        api = api,
        settings = settings,
        cache = FileModelCache(File(filesDir, "models_cache.json")),
    )
    val engine: ChatEngine = ChatEngineImpl(
        chats = chats,
        models = models,
        settings = settings,
        api = api,
        scope = appScope,
    )

    init {
        // En arrière-plan: l'UI affiche tout de suite les modèles du cache disque.
        appScope.launch {
            try {
                chats.recoverInterrupted()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Non bloquant.
            }
            try {
                if (!settings.apiKey().isNullOrBlank() && models.refresh().isSuccess) {
                    applyDefaultModelIfNeeded(settings, models)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Hors-ligne: on garde le cache.
            }
        }
    }
}
