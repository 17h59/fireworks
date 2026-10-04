package app.fwchat.ui.chat

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fwchat.domain.AppDefaults
import app.fwchat.domain.Chat
import app.fwchat.domain.ChatEngine
import app.fwchat.domain.ChatRepository
import app.fwchat.domain.GenParams
import app.fwchat.domain.ModelInfo
import app.fwchat.domain.ModelRepository
import app.fwchat.domain.SettingsRepository
import app.fwchat.domain.StreamingText
import app.fwchat.domain.SystemPrompt
import app.fwchat.domain.SystemPromptRepository
import app.fwchat.domain.ThreadItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZonedDateTime

/** Ce que l'écran affiche. Les textes en cours de streaming n'y sont PAS: voir [ChatViewModel.streaming]. */
@Immutable
data class ChatUiState(
    val chatId: String?,
    val isDraft: Boolean,
    /** false tant que le chat (ou les valeurs par défaut du brouillon) n'est pas chargé. */
    val loaded: Boolean = false,
    /** Chat supprimé (ou introuvable) alors qu'on l'affichait. */
    val notFound: Boolean = false,
    /** La lecture du chat a échoué (ex. base illisible, ligne trop grosse): afficher un message, pas planter. */
    val loadError: Boolean = false,
    /** Vide = « Nouveau chat ». */
    val title: String = "",
    val modelId: String? = null,
    /** false si le modèle du chat n'est plus dans la liste (et que la liste est chargée). */
    val modelAvailable: Boolean = true,
    val models: List<ModelInfo> = emptyList(),
    val prompts: List<SystemPrompt> = emptyList(),
    /** Brouillon: id (bibliothèque) du prompt choisi, null = aucun. */
    val selectedPromptId: String? = null,
    /** Nom du prompt (brouillon: choisi; chat existant: snapshot figé). null = aucun. */
    val promptName: String? = null,
    /** Texte du prompt: pour un chat existant, le snapshot figé (jamais modifiable). */
    val promptText: String? = null,
    val promptLocked: Boolean = false,
    val params: GenParams = AppDefaults.GEN_PARAMS,
    val thread: List<ThreadItem> = emptyList(),
    val generating: Boolean = false,
    val editingMessageId: String? = null,
    val thinkingOverrides: Map<String, ThinkingMode> = emptyMap(),
    val canSend: Boolean = false,
)

sealed interface ChatUiEvent {
    /**
     * Chat créé (1er envoi d'un brouillon, [fromDraft] = true: l'écran peut alors vider son champ) ou fork:
     * la coque navigue (onChatCreated).
     */
    data class OpenChat(val chatId: String, val fromDraft: Boolean = false) : ChatUiEvent
    data class CopyText(val text: String, val confirmation: String) : ChatUiEvent
    data class Notice(val message: String) : ChatUiEvent
    data object ScrollToBottom : ChatUiEvent
}

/** Message de snackbar pour un événement du moteur. */
sealed interface EngineNotice {
    data object Unauthorized : EngineNotice
    data class Message(val text: String) : EngineNotice
}

/**
 * ViewModel de l'écran de chat. [chatId] null = brouillon en mémoire (rien en base avant le 1er envoi).
 *
 * @param appScope scope applicatif (facultatif): les écritures déclenchées ici (création du chat, réglages)
 *   y survivent à la fermeture de l'écran; par défaut `viewModelScope`.
 */
class ChatViewModel(
    private val chats: ChatRepository,
    private val prompts: SystemPromptRepository,
    private val settings: SettingsRepository,
    private val models: ModelRepository,
    private val engine: ChatEngine,
    val chatId: String?,
    appScope: CoroutineScope? = null,
    private val paramsDebounceMs: Long = 400,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now() },
) : ViewModel() {

    private val scope: CoroutineScope = appScope ?: viewModelScope

    // ---- sources locales
    private val draft = MutableStateFlow(ChatDraft(modelId = null, prompt = null, params = AppDefaults.GEN_PARAMS))
    private val draftMeta = MutableStateFlow(DraftMeta(ready = chatId != null, sending = false))
    private val localParams = MutableStateFlow<GenParams?>(null)
    private val thinking = MutableStateFlow<Map<String, ThinkingMode>>(emptyMap())
    private val editing = MutableStateFlow<String?>(null)

    private data class DraftMeta(val ready: Boolean, val sending: Boolean)

    private sealed interface ChatLoad {
        data object Loading : ChatLoad
        data class Loaded(val chat: Chat?) : ChatLoad
        data object Failed : ChatLoad
    }

    private val chatLoad: StateFlow<ChatLoad> =
        (if (chatId == null) flowOf(ChatLoad.Loaded(null)) else chats.observeChat(chatId).map { ChatLoad.Loaded(it) as ChatLoad })
            // Une exception SQLite (ligne trop grosse pour la CursorWindow…) ne doit pas faire tomber le viewModelScope.
            .catch { emit(ChatLoad.Failed) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, if (chatId == null) ChatLoad.Loaded(null) else ChatLoad.Loading)

    private val threadFailed = MutableStateFlow(false)

    /**
     * Fil de messages. Chaque checkpoint de la base (1/s pendant un streaming) re-émet tout le fil: on réutilise les
     * instances inchangées et on supprime les émissions identiques ([ThreadDiff]: ignore content/reasoning/updatedAt
     * d'un message en STREAMING, dont le texte vient de `engine.streaming`).
     */
    private val threadFlow: Flow<List<ThreadItem>> =
        if (chatId == null) {
            flowOf(emptyList())
        } else {
            var previous: List<ThreadItem> = emptyList()
            chats.observeThread(chatId)
                .map { next -> ThreadDiff.stabilize(previous, next).also { previous = it } }
                .distinctUntilChanged()
                .catch {
                    threadFailed.value = true
                    emit(emptyList())
                }
        }

    private val generatingFlow: Flow<Boolean> =
        engine.generatingChats.map { chatId != null && chatId in it }

    private val modelsFlow = models.models
    private val promptsFlow = prompts.observeAll()

    /** Texte live des messages en cours: lu directement par la liste (ne recompose que le message concerné). */
    val streaming: StateFlow<Map<String, StreamingText>> get() = engine.streaming

    /** Événements du moteur (clé API invalide, erreurs): l'écran visible les affiche en snackbar. */
    val engineNotices: Flow<EngineNotice> get() = engine.events.mapNotNull { it.noticeFor(chatId) }

    /**
     * Copie du brouillon de saisie: survit aux changements de configuration quand le texte est trop gros pour
     * `rememberSaveable` (voir [BoundedTextSaver]). Pas un état observable: l'écran reste propriétaire du champ.
     */
    var draftBackup: String = ""

    private val eventChannel = Channel<ChatUiEvent>(Channel.BUFFERED)
    val events: Flow<ChatUiEvent> = eventChannel.receiveAsFlow()

    val state: StateFlow<ChatUiState> = run {
        val core = combine(draft, draftMeta, chatLoad, threadFlow, generatingFlow) { d, meta, load, thread, gen ->
            Core(d, meta, load, thread, gen)
        }
        val extras = combine(modelsFlow, promptsFlow, localParams, thinking, editing) { m, p, lp, th, ed ->
            Extras(m, p, lp, th, ed)
        }
        combine(core, extras, threadFailed) { c, e, failed -> buildState(c, e, failed) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ChatUiState(chatId = chatId, isDraft = chatId == null))
    }

    private data class Core(
        val draft: ChatDraft,
        val meta: DraftMeta,
        val load: ChatLoad,
        val thread: List<ThreadItem>,
        val generating: Boolean,
    )

    private data class Extras(
        val models: List<ModelInfo>,
        val prompts: List<SystemPrompt>,
        val localParams: GenParams?,
        val thinking: Map<String, ThinkingMode>,
        val editing: String?,
    )

    private fun buildState(c: Core, e: Extras, threadFailed: Boolean): ChatUiState {
        if (chatId == null) {
            val d = c.draft
            return ChatUiState(
                chatId = null,
                isDraft = true,
                loaded = c.meta.ready,
                title = "",
                modelId = d.modelId,
                modelAvailable = d.modelId == null || e.models.isEmpty() || e.models.any { it.id == d.modelId },
                models = e.models,
                prompts = e.prompts,
                selectedPromptId = d.prompt?.id,
                promptName = d.prompt?.name,
                promptText = d.prompt?.text,
                promptLocked = false,
                params = d.params,
                generating = false,
                canSend = c.meta.ready && d.modelId != null && !c.meta.sending,
            )
        }
        val chat = (c.load as? ChatLoad.Loaded)?.chat
        val failed = c.load is ChatLoad.Failed || threadFailed
        val loaded = c.load is ChatLoad.Loaded || failed
        return ChatUiState(
            chatId = chatId,
            isDraft = false,
            loaded = loaded,
            loadError = failed,
            notFound = c.load is ChatLoad.Loaded && chat == null && !failed,
            title = chat?.title.orEmpty(),
            modelId = chat?.modelId,
            modelAvailable = chat == null || e.models.isEmpty() || e.models.any { it.id == chat.modelId },
            models = e.models,
            prompts = e.prompts,
            promptName = chat?.systemPromptName,
            promptText = chat?.systemPromptText,
            promptLocked = true,
            params = e.localParams ?: chat?.params ?: AppDefaults.GEN_PARAMS,
            thread = c.thread,
            generating = c.generating,
            editingMessageId = e.editing,
            thinkingOverrides = e.thinking,
            canSend = chat != null && !c.generating && !failed,
        )
    }

    init {
        if (chatId == null) {
            viewModelScope.launch { initDraft() }
            viewModelScope.launch {
                // La liste de modèles peut arriver après: si aucun modèle n'est choisi, prendre le premier.
                models.models.collect { list ->
                    val first = list.firstOrNull()?.id
                    if (first != null && draft.value.modelId == null) draft.update { it.copy(modelId = first) }
                }
            }
        } else {
            viewModelScope.launch {
                // Quand la base reflète les paramètres locaux, on cesse de les superposer.
                chatLoad.collect { load ->
                    val chat = (load as? ChatLoad.Loaded)?.chat ?: return@collect
                    if (localParams.value == chat.params) localParams.value = null
                }
            }
        }
    }

    private suspend fun initDraft() {
        try {
            val s = settings.settings.first()
            val prompt = s.defaultSystemPromptId?.let { prompts.get(it) }
            val first = models.models.first().firstOrNull()?.id
            draft.value = ChatDraft(
                modelId = s.defaultModelId ?: first,
                prompt = prompt,
                params = s.defaultParams,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notice("Impossible de charger les réglages par défaut.")
        } finally {
            draftMeta.update { it.copy(ready = true) }
        }
    }

    // ------------------------------------------------------------------ envoi

    /**
     * Envoie [text]. Brouillon: crée le chat (modèle, snapshot du prompt résolu, paramètres) puis envoie puis
     * demande la navigation. Retourne true si l'envoi est accepté.
     *
     * Chat existant: l'écran peut vider son champ tout de suite. Brouillon: la création du chat peut encore échouer
     * (le texte ne doit alors PAS être perdu) — l'écran ne vide son champ qu'à l'événement
     * [ChatUiEvent.OpenChat] (`fromDraft`), voir [clearsDraftImmediately].
     */
    fun send(text: String): Boolean {
        if (text.isBlank()) return false
        if (chatId != null) {
            if (state.value.generating || !state.value.canSend) return false
            engine.send(chatId, text)
            eventChannel.trySend(ChatUiEvent.ScrollToBottom)
            return true
        }
        val d = draft.value
        val modelId = d.modelId
        if (modelId == null) {
            notice("Aucun modèle disponible : rechargez la liste depuis la pastille du modèle.")
            return false
        }
        val meta = draftMeta.value
        if (meta.sending || !meta.ready) return false
        draftMeta.value = meta.copy(sending = true)
        scope.launch {
            try {
                val id = chats.createChat(modelId, d.promptSnapshot(clock()), d.params)
                engine.send(id, text)
                eventChannel.send(ChatUiEvent.OpenChat(id, fromDraft = true))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                draftMeta.update { it.copy(sending = false) }
                notice("Impossible de créer le chat : ${e.message ?: e::class.java.simpleName}")
            }
        }
        return true
    }

    fun stop() {
        chatId?.let { engine.stop(it) }
    }

    // ------------------------------------------------------------------ messages

    fun regenerate(assistantMessageId: String) {
        if (chatId == null || state.value.generating) return
        engine.regenerate(assistantMessageId)
        eventChannel.trySend(ChatUiEvent.ScrollToBottom)
    }

    /**
     * « Réessayer » sur un message assistant en erreur. Le contrat ne permet de relancer qu'en créant un frère
     * (`regenerate` -> `addAssistantSibling`): sans précaution, chaque échec laisserait un bloc d'erreur de plus
     * (« 1/4 »). Choix: on crée le frère comme d'habitude, puis, quand la nouvelle génération est terminée
     * (COMPLETE ou ERROR; pas INTERRUPTED, où l'utilisateur a pu vouloir garder les deux), l'ancien message en
     * erreur — s'il n'a aucun contenu utile — est supprimé (`deleteSubtree`). Il ne reste ainsi qu'une réponse.
     */
    fun retry(failedMessageId: String) {
        val id = chatId ?: return
        if (state.value.generating) return
        val failed = state.value.thread.firstOrNull { it.message.id == failedMessageId }?.message ?: return
        engine.regenerate(failedMessageId)
        eventChannel.trySend(ChatUiEvent.ScrollToBottom)
        if (!RetryCleanup.isDroppable(failed)) return
        scope.launch {
            try {
                val finished = withTimeoutOrNull(RETRY_CLEANUP_TIMEOUT_MS) {
                    chats.observeThread(id).first { RetryCleanup.replacement(it, failedMessageId) != null }
                }?.let { RetryCleanup.replacement(it, failedMessageId) }
                if (finished != null && RetryCleanup.shouldDropFailed(finished)) chats.deleteSubtree(failedMessageId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // nettoyage facultatif: ne jamais gêner l'utilisateur
            }
        }
    }

    fun startEdit(messageId: String) {
        if (chatId == null || state.value.generating) return
        editing.value = messageId
    }

    fun cancelEdit() {
        editing.value = null
    }

    /** « Enregistrer »: modifie le texte sur place (pas de branche, pas de régénération). */
    fun saveEdit(messageId: String, newText: String) {
        editing.value = null
        if (newText.isBlank()) return
        scope.launch { runCatchingNotice { chats.editInPlace(messageId, newText) } }
    }

    /** « Envoyer » sur un message utilisateur: crée la branche et régénère. */
    fun sendEdit(messageId: String, newText: String) {
        if (newText.isBlank() || state.value.generating) return
        editing.value = null
        engine.editAndResend(messageId, newText)
        eventChannel.trySend(ChatUiEvent.ScrollToBottom)
    }

    fun deleteMessage(messageId: String) {
        if (state.value.generating) return
        scope.launch { runCatchingNotice { chats.deleteSubtree(messageId) } }
    }

    fun fork(messageId: String) {
        val id = chatId ?: return
        scope.launch {
            runCatchingNotice {
                val newId = chats.fork(id, messageId)
                eventChannel.send(ChatUiEvent.OpenChat(newId))
            }
        }
    }

    /** Flèches « ‹ 2/3 › »: [delta] = -1 ou +1. */
    fun selectSibling(item: ThreadItem, delta: Int) {
        if (state.value.generating) return
        val target = item.siblingIds.getOrNull(item.siblingIndex + delta) ?: return
        scope.launch { runCatchingNotice { chats.selectSibling(target) } }
    }

    // ------------------------------------------------------------------ thinking

    fun toggleThinking(messageId: String, current: ThinkingMode) {
        setThinking(messageId, ThinkingStates.onHamburger(current))
    }

    fun showAllThinking(messageId: String, current: ThinkingMode) {
        setThinking(messageId, ThinkingStates.onShowAll(current))
    }

    fun shrinkThinking(messageId: String, current: ThinkingMode) {
        setThinking(messageId, ThinkingStates.onShrink(current))
    }

    private fun setThinking(messageId: String, mode: ThinkingMode) {
        thinking.update { it + (messageId to mode) }
    }

    // ------------------------------------------------------------------ chat: titre, modèle, prompt, paramètres

    fun rename(title: String) {
        val id = chatId ?: return
        scope.launch { runCatchingNotice { chats.renameChat(id, title.trim()) } }
    }

    fun copyConversation() {
        val s = state.value
        val text = ConversationFormatter.format(
            title = s.title,
            modelId = s.modelId,
            promptName = s.promptName,
            messages = s.thread.map { it.message },
            live = engine.streaming.value,
        )
        eventChannel.trySend(ChatUiEvent.CopyText(text, "Conversation copiée"))
    }

    /** Modèle: brouillon = en mémoire; chat existant = `updateChatSettings`. Toujours modifiable. */
    fun setModel(modelId: String) {
        if (chatId == null) {
            draft.update { it.copy(modelId = modelId) }
        } else {
            scope.launch { runCatchingNotice { chats.updateChatSettings(chatId, modelId = modelId) } }
        }
    }

    /** Prompt système: UNIQUEMENT pour un brouillon (verrouillé ensuite). null = aucun. */
    fun setPrompt(promptId: String?) {
        if (chatId != null) return
        val prompt = promptId?.let { id -> state.value.prompts.firstOrNull { it.id == id } }
        draft.update { it.copy(prompt = prompt) }
    }

    private var pendingParams: GenParams? = null
    private var saveJob: Job? = null

    /** Brouillon: en mémoire. Chat existant: appliqué à l'affichage tout de suite, enregistré après [paramsDebounceMs]. */
    fun setParams(params: GenParams) {
        val id = chatId
        if (id == null) {
            draft.update { it.copy(params = params) }
            return
        }
        localParams.value = params
        pendingParams = params
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(paramsDebounceMs)
            persistParams(id)
        }
    }

    /** Enregistre tout de suite les paramètres en attente (fermeture de la feuille). */
    fun flushParams() {
        val id = chatId ?: return
        if (pendingParams == null) return
        saveJob?.cancel()
        scope.launch { persistParams(id) }
    }

    private suspend fun persistParams(id: String) {
        val p = pendingParams ?: return
        pendingParams = null
        runCatchingNotice { chats.updateChatSettings(id, params = p) }
    }

    suspend fun refreshModels(): Result<Unit> = models.refresh()

    // ------------------------------------------------------------------ utilitaires

    private companion object {
        const val RETRY_CLEANUP_TIMEOUT_MS = 30 * 60 * 1000L
    }

    private fun notice(message: String) {
        eventChannel.trySend(ChatUiEvent.Notice(message))
    }

    private suspend fun runCatchingNotice(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notice("Erreur : ${e.message ?: e::class.java.simpleName}")
        }
    }
}
