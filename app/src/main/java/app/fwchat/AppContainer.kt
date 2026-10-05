package app.fwchat

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
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
import app.fwchat.service.GenerationService
import app.fwchat.ui.shell.applyDefaultModelIfNeeded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
        // Streaming: un modèle qui réfléchit longtemps peut ne rien émettre pendant plusieurs minutes.
        .readTimeout(180, TimeUnit.SECONDS)
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
    private val settingsImpl = SettingsRepositoryImpl(
        dataStore = dataStore,
        secretStore = KeystoreSecretStore(File(filesDir, "secrets/api_key.bin")),
    )
    val settings: SettingsRepository = settingsImpl
    val models: ModelRepository = ModelRepositoryImpl(
        api = api,
        settings = settings,
        cache = FileModelCache(File(filesDir, "models_cache.json")),
    )
    /**
     * Terminé quand `recoverInterrupted()` a fini (succès ou échec): le moteur l'attend avant toute génération,
     * sinon la récupération pourrait marquer INTERRUPTED un message STREAMING légitime tout juste créé.
     */
    private val recovered = CompletableDeferred<Unit>()

    val engine: ChatEngine = ChatEngineImpl(
        chats = chats,
        models = models,
        settings = settings,
        api = api,
        scope = appScope,
        awaitReady = { recovered.await() },
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
            } finally {
                recovered.complete(Unit)
            }
            try {
                // Clé Keystore perdue: remet hasApiKey à faux (retour à l'onboarding) avant d'utiliser la clé.
                settingsImpl.reconcileApiKey()
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
        keepProcessAliveWhileGenerating()
    }

    /**
     * Tant qu'au moins un chat génère, un service de premier plan protège le processus (sinon Android gèle
     * l'app en arrière-plan et coupe la connexion). Le service s'arrête de lui-même quand plus rien ne génère
     * (voir [GenerationService]), ce qui évite la course démarrage/arrêt d'un stopService immédiat: on ne fait
     * donc que le démarrer ici, à chaque passage « rien ne génère » -> « une génération démarre ».
     */
    private fun keepProcessAliveWhileGenerating() {
        appScope.launch {
            engine.generatingChats
                .map { it.isNotEmpty() }
                .distinctUntilChanged()
                .collect { generating ->
                    if (!generating) return@collect
                    try {
                        ContextCompat.startForegroundService(appContext, Intent(appContext, GenerationService::class.java))
                    } catch (e: Exception) {
                        // Démarrage refusé (ex. interdit en arrière-plan): la génération continue, sans protection.
                    }
                }
        }
    }
}
