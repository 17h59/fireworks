package app.fwchat.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fwchat.domain.FireworksException
import app.fwchat.domain.ModelInfo
import app.fwchat.domain.ModelRepository
import app.fwchat.domain.Provider
import app.fwchat.domain.SettingsRepository
import app.fwchat.ui.settings.describeNetworkError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Petits modèles conversationnels connus (concurrence 1) préférés sur Featherless, dans l'ordre. */
private val FEATHERLESS_PREFERRED = listOf(
    "llama-3.1-8b-instruct", "qwen2.5-7b-instruct", "qwen3-8b", "mistral-7b-instruct", "gemma-2-9b-it",
)

/**
 * Modèle par défaut conseillé.
 * Fireworks: glm-5p3-flash, sinon gpt-oss-120b, sinon le premier de la liste.
 * Featherless: parmi les modèles utilisables sur le plan (ni verrouillés, ni hors plan), un petit modèle
 * conversationnel connu s'il est présent, sinon le premier utilisable, sinon le premier de la liste.
 */
fun pickDefaultModel(models: List<ModelInfo>, provider: Provider = Provider.FIREWORKS): String? = when (provider) {
    Provider.FIREWORKS ->
        (models.firstOrNull { "glm-5p3-flash" in it.id }
            ?: models.firstOrNull { "gpt-oss-120b" in it.id }
            ?: models.firstOrNull())?.id
    Provider.FEATHERLESS -> {
        val usable = models.filter { !it.gated && it.availableOnPlan != false }
        val preferred = FEATHERLESS_PREFERRED.firstNotNullOfOrNull { key ->
            usable.firstOrNull { key in it.id.lowercase() && (it.concurrencyCost ?: 1) <= 1 }
        }
        (preferred ?: usable.firstOrNull() ?: models.firstOrNull())?.id
    }
}

/**
 * Si le fournisseur n'a pas de modèle par défaut (ou s'il a disparu de sa liste), choisit celui de
 * [pickDefaultModel]. À appeler après un rafraîchissement réussi. [provider] null = fournisseur actif.
 */
suspend fun applyDefaultModelIfNeeded(settings: SettingsRepository, models: ModelRepository, provider: Provider? = null) {
    val s = settings.settings.first()
    val target = provider ?: s.activeProvider
    val list = models.modelsFor(target).first()
    val current = s.defaultModelIds[target] ?: if (target == s.activeProvider) s.defaultModelId else null
    if (current != null && list.any { it.id == current }) return
    pickDefaultModel(list, target)?.let { settings.setDefaultModel(target, it) }
}

enum class OnboardingError { EMPTY_KEY, UNAUTHORIZED, NETWORK, OTHER }

data class OnboardingState(
    val loading: Boolean = false,
    val error: OnboardingError? = null,
    /** Message lisible de l'erreur (nomme le bon fournisseur). */
    val message: String? = null,
    /** true quand la clé est validée: l'UI navigue vers chat/new. */
    val done: Boolean = false,
)

class OnboardingViewModel(
    private val settings: SettingsRepository,
    private val models: ModelRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    fun clearError() {
        _state.update { if (it.error != null) it.copy(error = null, message = null) else it }
    }

    /**
     * Enregistre la clé de [provider] puis vérifie qu'elle fonctionne en rechargeant ses modèles; efface la clé de CE
     * fournisseur en cas d'échec. Succès: fournisseur actif, modèle par défaut si absent, puis `done`.
     */
    fun submit(provider: Provider, rawKey: String) {
        val key = rawKey.trim()
        if (_state.value.loading || _state.value.done) return
        if (key.isEmpty()) {
            _state.value = OnboardingState(error = OnboardingError.EMPTY_KEY, message = "Colle ta clé API pour continuer.")
            return
        }
        _state.value = OnboardingState(loading = true)
        viewModelScope.launch {
            try {
                settings.setApiKey(provider, key)
                val result = models.refresh(provider)
                if (result.isSuccess) {
                    settings.setActiveProvider(provider)
                    if (provider == Provider.FEATHERLESS) runCatching { models.refreshPlan() }
                    applyDefaultModelIfNeeded(settings, models, provider)
                    _state.value = OnboardingState(done = true)
                } else {
                    val failure = result.exceptionOrNull()
                    settings.setApiKey(provider, null)
                    _state.value = OnboardingState(error = errorOf(failure), message = describeNetworkError(failure, provider))
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { runCatching { settings.setApiKey(provider, null) } }
                throw e
            } catch (e: Exception) {
                runCatching { settings.setApiKey(provider, null) }
                _state.value = OnboardingState(error = OnboardingError.OTHER, message = describeNetworkError(e, provider))
            }
        }
    }

    private fun errorOf(e: Throwable?): OnboardingError = when (e) {
        is FireworksException.Unauthorized -> OnboardingError.UNAUTHORIZED
        is FireworksException.Network -> OnboardingError.NETWORK
        is java.io.IOException -> OnboardingError.NETWORK
        else -> OnboardingError.OTHER
    }

    companion object {
        val Factory = containerFactory { c -> OnboardingViewModel(c.settings, c.models) }
    }
}
