package app.fwchat.ui.prompts.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fwchat.domain.SettingsRepository
import app.fwchat.domain.SystemPrompt
import app.fwchat.domain.SystemPromptRepository
import app.fwchat.ui.shell.containerFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PromptListState(
    val prompts: List<SystemPrompt> = emptyList(),
    /** null = « Aucun prompt système » par défaut. */
    val defaultId: String? = null,
    val loaded: Boolean = false,
)

class PromptListViewModel(
    private val prompts: SystemPromptRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    val state: StateFlow<PromptListState> = combine(
        prompts.observeAll(),
        settings.settings.map { it.defaultSystemPromptId }.distinctUntilChanged(),
    ) { list, defaultId ->
        // Un défaut qui pointe vers un prompt disparu vaut « aucun ».
        PromptListState(list, defaultId?.takeIf { id -> list.any { it.id == id } }, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PromptListState())

    /** Étoile d'un prompt: le définit par défaut, ou revient à « aucun » s'il l'était déjà. */
    fun toggleDefault(promptId: String) {
        viewModelScope.launch {
            val current = settings.settings.first().defaultSystemPromptId
            settings.setDefaultSystemPrompt(if (current == promptId) null else promptId)
        }
    }

    /** Étoile de la ligne « Aucun prompt système ». */
    fun setNoneAsDefault() {
        viewModelScope.launch { settings.setDefaultSystemPrompt(null) }
    }

    fun delete(promptId: String) {
        viewModelScope.launch {
            val current = settings.settings.first().defaultSystemPromptId
            prompts.delete(promptId)
            if (current == promptId) settings.setDefaultSystemPrompt(null)
        }
    }

    companion object {
        val Factory = containerFactory { c -> PromptListViewModel(c.prompts, c.settings) }
    }
}
