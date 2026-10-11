package app.fwchat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fwchat.domain.ChatRepository
import app.fwchat.domain.GenParams
import app.fwchat.domain.ModelInfo
import app.fwchat.domain.ModelRepository
import app.fwchat.domain.PlanInfo
import app.fwchat.domain.Provider
import app.fwchat.domain.SettingsRepository
import app.fwchat.domain.SystemPrompt
import app.fwchat.domain.SystemPromptRepository
import app.fwchat.ui.common.modelWarning
import app.fwchat.ui.shell.applyDefaultModelIfNeeded
import app.fwchat.ui.shell.containerFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Opération de sauvegarde en cours (une seule à la fois). */
enum class BackupOp { EXPORTING, IMPORTING }

data class SettingsUiState(
    val loaded: Boolean = false,
    val hasApiKey: Boolean = false,
    /** Mode actif: fournisseur des nouveaux chats, de la liste de modèles et du modèle par défaut. */
    val activeProvider: Provider = Provider.FIREWORKS,
    val providersWithKey: Set<Provider> = emptySet(),
    /** « •••• » + 4 derniers caractères par fournisseur, jamais la clé. */
    val keyHints: Map<Provider, String?> = emptyMap(),
    /** Vérification de la nouvelle clé en cours. */
    val validatingKey: Boolean = false,
    /** Fournisseur dont la clé est en cours de vérification (ou dont la dernière tentative a échoué). */
    val keyProvider: Provider? = null,
    /** Erreur de la dernière tentative d'ajout/remplacement (affichée dans le champ de [keyProvider]). */
    val keyError: String? = null,
    /** Modèle par défaut du fournisseur ACTIF. */
    val defaultModelId: String? = null,
    val models: List<ModelInfo> = emptyList(),
    val refreshingModels: Boolean = false,
    val defaultPromptId: String? = null,
    val prompts: List<SystemPrompt> = emptyList(),
    /** null tant que les réglages ne sont pas chargés. Valeur locale: reflète l'édition en cours (debounce). */
    val params: GenParams? = null,
    val backupOp: BackupOp? = null,
    /** Plan Featherless en cache (mode Featherless). */
    val plan: PlanInfo? = null,
    val planLoading: Boolean = false,
    val planError: String? = null,
)

/** Événements ponctuels (snackbar, navigation). */
sealed interface SettingsEvent {
    data class Message(val text: String) : SettingsEvent
    /** L'ajout ou le remplacement de clé a réussi: l'UI referme le champ. */
    data object KeyReplaced : SettingsEvent
    /** Plus aucune clé: l'UI renvoie vers l'onboarding. */
    data object ApiKeyRemoved : SettingsEvent
}

/**
 * Réglages. Les paramètres de génération sont modifiés localement tout de suite puis enregistrés après
 * [PARAMS_DEBOUNCE_MS]; [appScope] garantit l'enregistrement d'une modification en attente si l'écran est quitté.
 */
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val models: ModelRepository,
    private val prompts: SystemPromptRepository,
    private val chats: ChatRepository,
    private val appScope: CoroutineScope,
) : ViewModel() {

    private data class Local(
        val keyHints: Map<Provider, String?> = emptyMap(),
        val validatingKey: Boolean = false,
        val keyProvider: Provider? = null,
        val keyError: String? = null,
        val planLoading: Boolean = false,
        val planError: String? = null,
        val refreshingModels: Boolean = false,
        val params: GenParams? = null,
        val backupOp: BackupOp? = null,
    )

    private val local = MutableStateFlow(Local())
    private val eventChannel = Channel<SettingsEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    private var paramsJob: Job? = null
    private var pendingParams: GenParams? = null

    val state: StateFlow<SettingsUiState> = combine(
        settings.settings, models.models, prompts.observeAll(), local, models.plan,
    ) { s, ms, ps, l, plan ->
        SettingsUiState(
            loaded = l.params != null,
            hasApiKey = s.hasApiKey,
            activeProvider = s.activeProvider,
            providersWithKey = s.providersWithKey,
            keyHints = l.keyHints,
            validatingKey = l.validatingKey,
            keyProvider = l.keyProvider,
            keyError = l.keyError,
            plan = plan,
            planLoading = l.planLoading,
            planError = l.planError,
            defaultModelId = s.defaultModelId,
            models = ms,
            refreshingModels = l.refreshingModels,
            // Un défaut dont le prompt a disparu vaut « aucun » (remis à zéro en base par l'init).
            defaultPromptId = s.defaultSystemPromptId?.takeUnless { isStaleDefaultPrompt(it, ps) },
            prompts = ps,
            params = l.params,
            backupOp = l.backupOp,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        viewModelScope.launch {
            // Valeur initiale des paramètres: lue une seule fois, ensuite l'état local fait foi.
            val initial = settings.settings.first().defaultParams
            local.update { if (it.params == null) it.copy(params = initial) else it }
        }
        // Indices de clés (•••• + 4 derniers) pour chaque fournisseur, relus quand l'ensemble des clés change.
        viewModelScope.launch {
            settings.settings.map { it.providersWithKey }.distinctUntilChanged().collect { refreshKeyHints() }
        }
        // Mode Featherless (avec clé): le plan est rechargé à l'ouverture et à chaque bascule.
        viewModelScope.launch {
            settings.settings
                .map { it.activeProvider == Provider.FEATHERLESS && Provider.FEATHERLESS in it.providersWithKey }
                .distinctUntilChanged()
                .collect { if (it) loadPlan() }
        }
        // Prompt par défaut supprimé (depuis la bibliothèque ou par un import): retour à « Aucun prompt système ».
        viewModelScope.launch {
            combine(settings.settings.map { it.defaultSystemPromptId }.distinctUntilChanged(), prompts.observeAll()) { id, ps ->
                isStaleDefaultPrompt(id, ps)
            }.collect { stale ->
                if (stale) {
                    try {
                        settings.setDefaultSystemPrompt(null)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Non bloquant: l'affichage retombe déjà sur « aucun ».
                    }
                }
            }
        }
    }

    private suspend fun refreshKeyHints() {
        val hints = HashMap<Provider, String?>()
        for (p in Provider.entries) {
            hints[p] = try {
                maskApiKey(settings.apiKey(p))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        local.update { it.copy(keyHints = hints) }
    }

    private fun post(text: String) {
        eventChannel.trySend(SettingsEvent.Message(text))
    }

    // ------------------------------------------------------------------ fournisseur et clés API

    fun clearKeyError() {
        local.update { if (it.keyError != null) it.copy(keyError = null, keyProvider = null) else it }
    }

    /** Segment « Fireworks | Featherless »: change de mode. Sans clé pour ce fournisseur, rien (l'UI ouvre l'ajout). */
    fun selectProvider(provider: Provider) {
        viewModelScope.launch {
            try {
                val s = settings.settings.first()
                if (provider in s.providersWithKey && provider != s.activeProvider) settings.setActiveProvider(provider)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                post("Impossible de changer de mode. Réessaie.")
            }
        }
    }

    /**
     * Enregistre la clé de [provider] (ajout ou remplacement) puis vérifie qu'elle fonctionne en rechargeant ses
     * modèles. En cas d'échec (ou d'annulation), l'ancienne clé de CE fournisseur est restaurée (ou effacée).
     * Une clé ajoutée pour un fournisseur qui n'en avait pas active son mode.
     */
    fun replaceKey(provider: Provider, rawKey: String) {
        val key = rawKey.trim()
        if (local.value.validatingKey) return
        if (key.isEmpty()) {
            local.update { it.copy(keyProvider = provider, keyError = "Colle ta clé API ${provider.label} pour continuer.") }
            return
        }
        local.update { it.copy(validatingKey = true, keyProvider = provider, keyError = null) }
        viewModelScope.launch {
            val old = try {
                settings.apiKey(provider)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            var success = false
            var failure: Throwable? = null
            try {
                settings.setApiKey(provider, key)
                val result = models.refresh(provider)
                if (result.isSuccess) {
                    success = true
                } else {
                    failure = result.exceptionOrNull()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
            } finally {
                if (!success) {
                    withContext(NonCancellable) { runCatching { settings.setApiKey(provider, old) } }
                }
            }
            if (success) {
                runCatching { applyDefaultModelIfNeeded(settings, models, provider) }
                var switched = false
                if (old == null) {
                    // Nouveau fournisseur: on passe dessus (la clé vient d'être validée, donc l'activation est acceptée).
                    switched = runCatching {
                        if (settings.settings.first().activeProvider != provider) {
                            settings.setActiveProvider(provider)
                            true
                        } else false
                    }.getOrDefault(false)
                }
                if (provider == Provider.FEATHERLESS) runCatching { models.refreshPlan() }
                refreshKeyHints()
                local.update { it.copy(validatingKey = false, keyProvider = null, keyError = null) }
                eventChannel.trySend(SettingsEvent.KeyReplaced)
                post(
                    "Clé ${provider.label} enregistrée : ${provider.label} l'a acceptée." +
                        if (switched) " Mode ${provider.label} activé." else "",
                )
            } else {
                local.update {
                    it.copy(validatingKey = false, keyProvider = provider, keyError = describeNetworkError(failure, provider))
                }
            }
        }
    }

    /**
     * Efface la clé de [provider]. Si c'était celle du mode actif et que l'autre fournisseur a une clé, on bascule
     * dessus; sans aucune clé, l'UI est renvoyée vers l'onboarding via [SettingsEvent.ApiKeyRemoved].
     */
    fun removeKey(provider: Provider) {
        viewModelScope.launch {
            val wasActive: Boolean
            try {
                wasActive = settings.settings.first().activeProvider == provider
                settings.setApiKey(provider, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                post("Impossible de supprimer la clé. Réessaie.")
                return@launch
            }
            local.update { it.copy(keyProvider = null, keyError = null) }
            refreshKeyHints()
            val s = settings.settings.first()
            if (!s.hasApiKey) {
                eventChannel.trySend(SettingsEvent.ApiKeyRemoved)
                return@launch
            }
            var switchedTo: Provider? = null
            if (wasActive && s.activeProvider == provider) {
                // Le dépôt bascule déjà; on s'en assure si ce n'est pas le cas.
                val other = s.providersWithKey.firstOrNull()
                if (other != null) {
                    runCatching { settings.setActiveProvider(other) }
                    switchedTo = other
                }
            } else if (wasActive) {
                switchedTo = s.activeProvider
            }
            post("Clé ${provider.label} supprimée." + (switchedTo?.let { " Mode ${it.label} activé." } ?: ""))
        }
    }

    // ------------------------------------------------------------------ modèle et prompt par défaut

    /** Modèle par défaut du fournisseur ACTIF; avertit si le modèle est verrouillé ou hors plan. */
    fun setDefaultModel(modelId: String) {
        viewModelScope.launch {
            settings.setDefaultModel(settings.settings.first().activeProvider, modelId)
            modelWarning(state.value.models.firstOrNull { it.id == modelId })?.let { post(it) }
        }
    }

    /** Recharge le plan Featherless (état dans [SettingsUiState.plan] / planLoading / planError). */
    fun loadPlan() {
        if (local.value.planLoading) return
        local.update { it.copy(planLoading = true, planError = null) }
        viewModelScope.launch {
            try {
                val r = models.refreshPlan()
                local.update {
                    it.copy(planLoading = false, planError = r.exceptionOrNull()?.let { e -> describeNetworkError(e, Provider.FEATHERLESS) })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                local.update { it.copy(planLoading = false, planError = describeNetworkError(e, Provider.FEATHERLESS)) }
            }
        }
    }

    fun setDefaultPrompt(promptId: String?) {
        viewModelScope.launch { settings.setDefaultSystemPrompt(promptId) }
    }

    /** Pour la feuille de choix du modèle (qui affiche elle-même son spinner et son erreur). */
    suspend fun refreshModelsForSheet(): Result<Unit> {
        val provider = settings.settings.first().activeProvider
        val result = try {
            models.refresh(provider)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        if (result.isSuccess && provider == Provider.FEATHERLESS) runCatching { models.refreshPlan() }
        // Les erreurs lisibles remplacent les messages bruts de l'API.
        return result.fold({ result }, { Result.failure(IllegalStateException(describeNetworkError(it, provider))) })
    }

    /** Bouton « Recharger la liste des modèles »: résultat en message (nombre de modèles ou erreur). */
    fun refreshModels() {
        if (local.value.refreshingModels) return
        local.update { it.copy(refreshingModels = true) }
        viewModelScope.launch {
            var provider = Provider.FIREWORKS
            try {
                provider = settings.settings.first().activeProvider
                val result = models.refresh(provider)
                if (result.isSuccess) {
                    runCatching { applyDefaultModelIfNeeded(settings, models, provider) }
                    if (provider == Provider.FEATHERLESS) runCatching { models.refreshPlan() }
                    post(modelCountLabel(models.modelsFor(provider).first().size) + ".")
                } else {
                    post(describeNetworkError(result.exceptionOrNull(), provider))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                post(describeNetworkError(e, provider))
            } finally {
                local.update { it.copy(refreshingModels = false) }
            }
        }
    }

    // ------------------------------------------------------------------ paramètres de génération

    fun onParamsChange(params: GenParams) {
        local.update { it.copy(params = params) }
        pendingParams = params
        paramsJob?.cancel()
        paramsJob = viewModelScope.launch {
            delay(PARAMS_DEBOUNCE_MS)
            val p = pendingParams ?: return@launch
            settings.setDefaultParams(p)
            if (pendingParams === p) pendingParams = null
        }
    }

    /** Enregistre tout de suite une modification en attente (sortie de l'écran). */
    fun flushParams() {
        val p = pendingParams ?: return
        paramsJob?.cancel()
        pendingParams = null
        appScope.launch { runCatching { settings.setDefaultParams(p) } }
    }

    override fun onCleared() {
        flushParams()
    }

    // ------------------------------------------------------------------ sauvegarde

    /** [write] reçoit le JSON et l'écrit dans le fichier choisi (l'appelant gère le thread d'E/S). */
    fun export(write: suspend (String) -> Unit) {
        if (local.value.backupOp != null) return
        local.update { it.copy(backupOp = BackupOp.EXPORTING) }
        viewModelScope.launch {
            try {
                write(chats.exportAll())
                post("Sauvegarde exportée.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                post("L'export a échoué. Vérifie l'espace disponible et réessaie.")
            } finally {
                local.update { it.copy(backupOp = null) }
            }
        }
    }

    /** [read] renvoie le contenu du fichier choisi (l'appelant gère le thread d'E/S). */
    fun import(read: suspend () -> String, replace: Boolean) {
        if (local.value.backupOp != null) return
        local.update { it.copy(backupOp = BackupOp.IMPORTING) }
        viewModelScope.launch {
            try {
                val json = read()
                chats.importAll(json, replace)
                post(
                    if (replace) "Sauvegarde importée : tes données ont été remplacées."
                    else "Sauvegarde fusionnée avec tes données.",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                post("Ce fichier n'est pas une sauvegarde FW Chat valide ou provient d'une version plus récente. Rien n'a été modifié.")
            } catch (e: Exception) {
                post("L'import a échoué. Rien n'a été modifié.")
            } finally {
                local.update { it.copy(backupOp = null) }
            }
        }
    }

    companion object {
        const val PARAMS_DEBOUNCE_MS = 400L

        val Factory = containerFactory { c ->
            SettingsViewModel(c.settings, c.models, c.prompts, c.chats, c.appScope)
        }
    }
}
