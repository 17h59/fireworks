package app.fwchat

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.fwchat.data.db.AppDatabase
import app.fwchat.data.db.build
import app.fwchat.data.net.BillingRepositoryImpl
import app.fwchat.data.net.ChatEngineImpl
import app.fwchat.data.net.FileBillingStore
import app.fwchat.data.net.CompactModelCache
import app.fwchat.data.net.FeatherlessApiImpl
import app.fwchat.data.net.FileModelCache
import app.fwchat.data.net.FilePlanCache
import app.fwchat.data.net.FireworksApiImpl
import app.fwchat.data.net.ModelRepositoryImpl
import app.fwchat.data.prefs.KeystoreSecretStore
import app.fwchat.data.prefs.SettingsRepositoryImpl
import app.fwchat.data.repo.ChatRepositoryImpl
import app.fwchat.data.repo.SystemPromptRepositoryImpl
import app.fwchat.domain.BillingRepository
import app.fwchat.domain.ChatEngine
import app.fwchat.domain.ChatRepository
import app.fwchat.domain.ModelRepository
import app.fwchat.domain.Provider
import app.fwchat.domain.ProviderApis
import app.fwchat.domain.SettingsRepository
import app.fwchat.domain.SystemPromptRepository
import app.fwchat.service.GenerationService
import app.fwchat.ui.shell.applyDefaultModelIfNeeded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
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

    private val apis = ProviderApis(
        mapOf(
            Provider.FIREWORKS to FireworksApiImpl(httpClient, baseUrl = "https://api.fireworks.ai"),
            Provider.FEATHERLESS to FeatherlessApiImpl(httpClient, baseUrl = "https://api.featherless.ai"),
        ),
    )

    private val database: AppDatabase = AppDatabase.build(appContext)

    private val dataStore = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        produceFile = { File(filesDir, "datastore/settings.preferences_pb") },
    )

    val chats: ChatRepository = ChatRepositoryImpl(database)
    val prompts: SystemPromptRepository = SystemPromptRepositoryImpl(database)
    private val settingsImpl = SettingsRepositoryImpl(
        dataStore = dataStore,
        secretStores = mapOf(
            // Fichier et alias historiques: la clé Fireworks d'un utilisateur existant reste lisible.
            Provider.FIREWORKS to KeystoreSecretStore(File(filesDir, "secrets/api_key.bin")),
            Provider.FEATHERLESS to KeystoreSecretStore(
                File(filesDir, "secrets/api_key_featherless.bin"), alias = "fwchat_api_key_featherless_v1",
            ),
        ),
    )
    val settings: SettingsRepository = settingsImpl
    val models: ModelRepository = ModelRepositoryImpl(
        apis = apis,
        settings = settings,
        caches = mapOf(
            Provider.FIREWORKS to FileModelCache(File(filesDir, "models_cache.json")),
            Provider.FEATHERLESS to CompactModelCache(File(filesDir, "models_cache_featherless.json")),
        ),
        planCache = FilePlanCache(File(filesDir, "featherless_plan.json")),
    )
    /** Dépenses et crédit estimé (lecture seule, affichés uniquement dans les Réglages). */
    val billing: BillingRepository = BillingRepositoryImpl(
        client = httpClient,
        baseUrl = "https://api.fireworks.ai",
        settings = settings,
        store = FileBillingStore(File(filesDir, "billing_cache.json")),
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
        apis = apis,
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
                // Fournisseur actif seulement, et seulement s'il a une clé. La liste Featherless pèse ~2,4 Mo:
                // elle n'est rechargée au démarrage que si le cache a plus de 6 h.
                val active = settings.settings.first().activeProvider
                if (!settings.apiKey(active).isNullOrBlank()) {
                    val result = if (active == Provider.FEATHERLESS) {
                        models.refreshIfStale(active, STARTUP_MAX_AGE_MS)
                    } else {
                        models.refresh(active)
                    }
                    if (result.isSuccess) applyDefaultModelIfNeeded(settings, models)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Hors-ligne: on garde le cache.
            }
        }
        refreshModelsOnProviderSwitch()
        keepProcessAliveWhileGenerating()
        refreshBillingAfterGenerations()
    }

    /** À chaque bascule de fournisseur actif: recharge sa liste si son cache est vide ou ancien (et qu'il a une clé). */
    private fun refreshModelsOnProviderSwitch() {
        appScope.launch {
            settings.settings.map { it.activeProvider }.distinctUntilChanged().drop(1).collect { provider ->
                try {
                    if (models.refreshIfStale(provider).isSuccess) applyDefaultModelIfNeeded(settings, models)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Non bloquant.
                }
            }
        }
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

    /**
     * À chaque fin de génération (un chat quitte l'ensemble), relit les dépenses après ~2 s (le serveur met un
     * instant à comptabiliser), au plus une fois toutes les 5 s. Ne bloque jamais l'UI.
     */
    private fun refreshBillingAfterGenerations() {
        appScope.launch {
            var previous = emptySet<String>()
            var lastRefreshAt = 0L
            engine.generatingChats.collect { current ->
                val finishedIds = previous - current
                previous = current
                if (finishedIds.isNotEmpty()) {
                    launch {
                        delay(BILLING_REFRESH_DELAY_MS)
                        val now = System.currentTimeMillis()
                        if (now - lastRefreshAt < BILLING_MIN_INTERVAL_MS) return@launch
                        lastRefreshAt = now
                        try {
                            // La facturation n'existe que chez Fireworks: ignorée si les chats terminés sont Featherless.
                            val fireworksFinished = finishedIds.any { id ->
                                chats.getChat(id)?.modelId?.let { Provider.fromModelId(it) == Provider.FIREWORKS } == true
                            }
                            if (fireworksFinished && !settings.apiKey(Provider.FIREWORKS).isNullOrBlank()) billing.refresh()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Non bloquant.
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val BILLING_REFRESH_DELAY_MS = 2_000L
        const val BILLING_MIN_INTERVAL_MS = 5_000L
        const val STARTUP_MAX_AGE_MS = 6L * 60 * 60 * 1000
    }
}
