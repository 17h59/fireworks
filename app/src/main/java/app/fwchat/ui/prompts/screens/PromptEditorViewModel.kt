package app.fwchat.ui.prompts.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.fwchat.domain.PromptFamily
import app.fwchat.domain.SettingsRepository
import app.fwchat.domain.SystemPrompt
import app.fwchat.domain.SystemPromptRepository
import app.fwchat.ui.prompts.PromptTemplate
import app.fwchat.ui.prompts.PromptTemplates
import app.fwchat.ui.shell.Routes
import app.fwchat.ui.shell.appContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.ceil

/** Estimation grossière: ~3,5 caractères par jeton. */
fun estimateTokens(chars: Int): Int = ceil(chars / 3.5).toInt()

/** Familles proposées dans l'éditeur (null = « Autre / aucune »). */
val EditorFamilies: List<PromptFamily?> = listOf(
    PromptFamily.GLM, PromptFamily.DEEPSEEK, PromptFamily.QWEN, PromptFamily.KIMI,
    PromptFamily.GPT_OSS, PromptFamily.MINIMAX, PromptFamily.NEMOTRON, null,
)

class PromptEditorViewModel(
    private val prompts: SystemPromptRepository,
    private val settings: SettingsRepository,
    private val promptId: String,
    private val initialTemplateId: String?,
    /** Brouillon (nom, texte, famille): survit à la mort du processus tant que l'écran est dans la pile. */
    private val handle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {

    val isNew: Boolean = promptId == Routes.PROMPT_NEW_ID

    var name by mutableStateOf("")
        private set
    var text by mutableStateOf("")
        private set
    /** null = « Autre / aucune ». */
    var family: PromptFamily? by mutableStateOf(null)
        private set
    var loaded by mutableStateOf(isNew)
        private set
    /** true si le prompt demandé n'existe pas (supprimé entre-temps): l'écran se ferme. */
    var notFound by mutableStateOf(false)
        private set

    private var original: SystemPrompt? = null
    private var baseName = ""
    private var baseText = ""
    private var baseFamily: PromptFamily? = null

    /** true pendant l'enregistrement: un double appui sur « Enregistrer » ne crée qu'un seul prompt. */
    var saving by mutableStateOf(false)
        private set

    val canSave: Boolean get() = name.isNotBlank() && text.isNotBlank()
    val dirty: Boolean get() = name != baseName || text != baseText || family != baseFamily

    init {
        // Brouillon restauré (processus tué): chaque champ saisi l'emporte sur le modèle ou la version enregistrée.
        val draftName = handle.get<String>(KEY_NAME)
        val draftText = handle.get<String>(KEY_TEXT)
        val draftFamily = handle.contains(KEY_FAMILY)
        val hasDraft = draftName != null || draftText != null || draftFamily
        fun restoredFamily(): PromptFamily? =
            handle.get<String>(KEY_FAMILY)?.let { f -> PromptFamily.entries.firstOrNull { it.name == f } }.normalized()
        if (draftName != null) name = draftName
        if (draftText != null) text = draftText
        if (draftFamily) family = restoredFamily()
        if (isNew) {
            if (!hasDraft) {
                PromptTemplates.all.firstOrNull { it.id == initialTemplateId }?.let { t ->
                    name = t.name
                    text = t.text
                    family = t.family.normalized()
                }
            }
        } else {
            viewModelScope.launch {
                val p = prompts.get(promptId)
                if (p == null) {
                    notFound = true
                } else {
                    original = p
                    baseName = p.name; baseText = p.text; baseFamily = p.family.normalized()
                    if (draftName == null) name = baseName
                    if (draftText == null) text = baseText
                    if (!draftFamily) family = baseFamily
                }
                loaded = true
            }
        }
    }

    fun onNameChange(v: String) { name = v; handle[KEY_NAME] = v }
    fun onTextChange(v: String) { text = v; handle[KEY_TEXT] = v }
    fun onFamilyChange(f: PromptFamily?) { family = f; handle[KEY_FAMILY] = f?.name }

    /** Remplit le champ avec un modèle. Si le nom est vide, reprend aussi celui du modèle. */
    fun applyTemplate(t: PromptTemplate) {
        onTextChange(t.text)
        if (name.isBlank()) onNameChange(t.name)
    }

    fun save(onSaved: () -> Unit) {
        if (!canSave || saving) return
        saving = true
        val now = System.currentTimeMillis()
        val draft = SystemPrompt(
            id = original?.id ?: "",
            name = name.trim(),
            text = text,
            family = family,
            createdAt = original?.createdAt ?: now,
            updatedAt = now,
        )
        viewModelScope.launch {
            try {
                val id = prompts.upsert(draft)
                original = draft.copy(id = id)
                baseName = name; baseText = text; baseFamily = family
            } finally {
                saving = false
            }
            onSaved()
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val id = original?.id ?: return
        viewModelScope.launch {
            val current = settings.settings.first().defaultSystemPromptId
            prompts.delete(id)
            if (current == id) settings.setDefaultSystemPrompt(null)
            onDeleted()
        }
    }

    companion object {
        private const val KEY_NAME = "draft_name"
        private const val KEY_TEXT = "draft_text"
        private const val KEY_FAMILY = "draft_family"

        val Factory = viewModelFactory {
            initializer {
                val handle: SavedStateHandle = createSavedStateHandle()
                val c = appContainer()
                PromptEditorViewModel(
                    c.prompts,
                    c.settings,
                    handle.get<String>(Routes.PROMPT_ARG) ?: Routes.PROMPT_NEW_ID,
                    handle.get<String>(Routes.PROMPT_TEMPLATE_ARG),
                    handle,
                )
            }
        }
    }
}

private fun PromptFamily?.normalized(): PromptFamily? = if (this == PromptFamily.OTHER) null else this
