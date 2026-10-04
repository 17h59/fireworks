package app.fwchat.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fwchat.domain.FireworksException
import app.fwchat.domain.ModelInfo
import app.fwchat.domain.ModelRepository
import app.fwchat.domain.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Modèle par défaut conseillé: glm-5p3-flash, sinon gpt-oss-120b, sinon le premier de la liste. */
fun pickDefaultModel(models: List<ModelInfo>): String? =
    (models.firstOrNull { "glm-5p3-flash" in it.id }
        ?: models.firstOrNull { "gpt-oss-120b" in it.id }
        ?: models.firstOrNull())?.id

/**
 * Si aucun modèle par défaut n'est défini (ou s'il a disparu de la liste), choisit celui de [pickDefaultModel].
 * À appeler après un rafraîchissement réussi.
 */
suspend fun applyDefaultModelIfNeeded(settings: SettingsRepository, models: ModelRepository) {
    val list = models.models.first()
    val current = settings.settings.first().defaultModelId
    if (current != null && list.any { it.id == current }) return
    pickDefaultModel(list)?.let { settings.setDefaultModel(it) }
}

enum class OnboardingError { EMPTY_KEY, UNAUTHORIZED, NETWORK, OTHER }

data class OnboardingState(
    val loading: Boolean = false,
    val error: OnboardingError? = null,
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
        _state.update { if (it.error != null) it.copy(error = null) else it }
    }

    /** Enregistre la clé puis vérifie qu'elle fonctionne en rechargeant les modèles; efface la clé en cas d'échec. */
    fun submit(rawKey: String) {
        val key = rawKey.trim()
        if (_state.value.loading || _state.value.done) return
        if (key.isEmpty()) {
            _state.value = OnboardingState(error = OnboardingError.EMPTY_KEY)
            return
        }
        _state.value = OnboardingState(loading = true)
        viewModelScope.launch {
            try {
                settings.setApiKey(key)
                val result = models.refresh()
                if (result.isSuccess) {
                    applyDefaultModelIfNeeded(settings, models)
                    _state.value = OnboardingState(done = true)
                } else {
                    settings.setApiKey(null)
                    _state.value = OnboardingState(error = errorOf(result.exceptionOrNull()))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching { settings.setApiKey(null) }
                _state.value = OnboardingState(error = OnboardingError.OTHER)
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
