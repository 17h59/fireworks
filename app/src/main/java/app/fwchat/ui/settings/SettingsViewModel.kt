package app.fwchat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fwchat.domain.ChatRepository
import app.fwchat.domain.GenParams
import app.fwchat.domain.ModelInfo
import app.fwchat.domain.ModelRepository
import app.fwchat.domain.SettingsRepository
import app.fwchat.domain.SystemPrompt
import app.fwchat.domain.SystemPromptRepository
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
import kotlinx.coroutines.flow.first
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
    /** « •••• » + 4 derniers caractères, jamais la clé. */
    val keyHint: String? = null,
    /** Vérification de la nouvelle clé en cours. */
    val validatingKey: Boolean = false,
    /** Erreur de la dernière tentative de remplacement (affichée dans le champ). */
    val keyError: String? = null,
    val defaultModelId: String? = null,
    val models: List<ModelInfo> = emptyList(),
    val refreshingModels: Boolean = false,
    val defaultPromptId: String? = null,
    val prompts: List<SystemPrompt> = emptyList(),
    /** null tant que les réglages ne sont pas chargés. Valeur locale: reflète l'édition en cours (debounce). */
    val params: GenParams? = null,
    val backupOp: BackupOp? = null,
)

/** Événements ponctuels (snackbar, navigation). */
sealed interface SettingsEvent {
    data class Message(val text: String) : SettingsEvent
    /** Le remplacement de clé a réussi: l'UI referme le champ. */
    data object KeyReplaced : SettingsEvent
    /** La clé a été effacée: l'UI renvoie vers l'onboarding. */
    data object ApiKeyRemoved : SettingsEvent
}

/**
 * Réglages. Les paramètres de génération sont modifiés localement tout de suite puis enregistrés après
 * [PARAMS_DEBOUNCE_MS]; [appScope] garantit l'enregistrement d'une modification en attente si l'écran est quitté.
 */
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val models: ModelRepository,
    prompts: SystemPromptRepository,
    private val chats: ChatRepository,
    private val appScope: CoroutineScope,
) : ViewModel() {

    private data class Local(
        val keyHint: String? = null,
        val validatingKey: Boolean = false,
        val keyError: String? = null,
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
        settings.settings, models.models, prompts.observeAll(), local,
    ) { s, ms, ps, l ->
        SettingsUiState(
            loaded = l.params != null,
            hasApiKey = s.hasApiKey,
            keyHint = l.keyHint,
            validatingKey = l.validatingKey,
            keyError = l.keyError,
            defaultModelId = s.defaultModelId,
            models = ms,
            refreshingModels = l.refreshingModels,
            defaultPromptId = s.defaultSystemPromptId,
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
        viewModelScope.launch { refreshKeyHint() }
    }

    private suspend fun refreshKeyHint() {
        val hint = try {
            maskApiKey(settings.apiKey())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        local.update { it.copy(keyHint = hint) }
    }

    private fun post(text: String) {
        eventChannel.trySend(SettingsEvent.Message(text))
    }

    // ------------------------------------------------------------------ clé API

    fun clearKeyError() {
        local.update { if (it.keyError != null) it.copy(keyError = null) else it }
    }

    /**
     * Enregistre la nouvelle clé puis vérifie qu'elle fonctionne en rechargeant les modèles.
     * En cas d'échec (ou d'annulation), l'ancienne clé est restaurée.
     */
    fun replaceKey(rawKey: String) {
        val key = rawKey.trim()
        if (local.value.validatingKey) return
        if (key.isEmpty()) {
            local.update { it.copy(keyError = "Colle ta nouvelle clé API pour continuer.") }
            return
        }
        local.update { it.copy(validatingKey = true, keyError = null) }
        viewModelScope.launch {
            val old = try {
                settings.apiKey()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            var success = false
            var failure: Throwable? = null
            try {
                settings.setApiKey(key)
                val result = models.refresh()
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
                    withContext(NonCancellable) { runCatching { settings.setApiKey(old) } }
                }
            }
            if (success) {
                runCatching { applyDefaultModelIfNeeded(settings, models) }
                local.update { it.copy(validatingKey = false, keyError = null, keyHint = maskApiKey(key)) }
                eventChannel.trySend(SettingsEvent.KeyReplaced)
                post("Clé enregistrée : Fireworks l'a acceptée.")
            } else {
                local.update { it.copy(validatingKey = false, keyError = describeNetworkError(failure)) }
            }
        }
    }

    /** Efface la clé; l'UI est ensuite renvoyée vers l'onboarding via [SettingsEvent.ApiKeyRemoved]. */
    fun removeKey() {
        viewModelScope.launch {
            try {
                settings.setApiKey(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                post("Impossible de supprimer la clé. Réessaie.")
                return@launch
            }
            local.update { it.copy(keyHint = null, keyError = null) }
            eventChannel.trySend(SettingsEvent.ApiKeyRemoved)
        }
    }

    // ------------------------------------------------------------------ modèle et prompt par défaut

    fun setDefaultModel(modelId: String) {
        viewModelScope.launch { settings.setDefaultModel(modelId) }
    }

    fun setDefaultPrompt(promptId: String?) {
        viewModelScope.launch { settings.setDefaultSystemPrompt(promptId) }
    }

    /** Pour la feuille de choix du modèle (qui affiche elle-même son spinner et son erreur). */
    suspend fun refreshModelsForSheet(): Result<Unit> {
        val result = try {
            models.refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        // Les erreurs lisibles remplacent les messages bruts de l'API.
        return result.fold({ result }, { Result.failure(IllegalStateException(describeNetworkError(it))) })
    }

    /** Bouton « Recharger la liste des modèles »: résultat en message (nombre de modèles ou erreur). */
    fun refreshModels() {
        if (local.value.refreshingModels) return
        local.update { it.copy(refreshingModels = true) }
        viewModelScope.launch {
            try {
                val result = models.refresh()
                if (result.isSuccess) {
                    runCatching { applyDefaultModelIfNeeded(settings, models) }
                    post(modelCountLabel(models.models.first().size) + ".")
                } else {
                    post(describeNetworkError(result.exceptionOrNull()))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                post(describeNetworkError(e))
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
