package app.fwchat.data.net

import app.fwchat.domain.ApiMessage
import app.fwchat.domain.AppDefaults
import app.fwchat.domain.Chat
import app.fwchat.domain.ChatEngine
import app.fwchat.domain.ChatRepository
import app.fwchat.domain.ChatRequest
import app.fwchat.domain.EngineEvent
import app.fwchat.domain.FireworksApi
import app.fwchat.domain.FireworksException
import app.fwchat.domain.GenParams
import app.fwchat.domain.Message
import app.fwchat.domain.MessageStatus
import app.fwchat.domain.ModelRepository
import app.fwchat.domain.Role
import app.fwchat.domain.SettingsRepository
import app.fwchat.domain.StreamEvent
import app.fwchat.domain.StreamingText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Orchestre les générations. Une génération à la fois par chat; tout tourne dans [scope]
 * (scope applicatif), donc une génération survit à la navigation.
 *
 * @param publishIntervalMs période de publication de [streaming] (conflation).
 * @param checkpointIntervalMs période des checkpoints DB pendant le streaming.
 */
class ChatEngineImpl(
    private val chats: ChatRepository,
    private val models: ModelRepository,
    private val settings: SettingsRepository,
    private val api: FireworksApi,
    private val scope: CoroutineScope,
    private val publishIntervalMs: Long = 40,
    private val checkpointIntervalMs: Long = 1000,
    /**
     * Suspend jusqu'à ce que l'application soit prête à générer (ex.: `recoverInterrupted()` terminé,
     * pour qu'il ne marque pas INTERRUPTED un message légitime en cours). Attendu au début de chaque génération.
     */
    private val awaitReady: suspend () -> Unit = {},
) : ChatEngine {

    private val _streaming = MutableStateFlow<Map<String, StreamingText>>(emptyMap())
    override val streaming: StateFlow<Map<String, StreamingText>> = _streaming.asStateFlow()

    private val _generating = MutableStateFlow<Set<String>>(emptySet())
    override val generatingChats: StateFlow<Set<String>> = _generating.asStateFlow()

    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<EngineEvent> = _events.asSharedFlow()

    private val lock = Any()
    private val jobs = HashMap<String, Job>()

    // ------------------------------------------------------------------ API publique

    override fun send(chatId: String, text: String) {
        if (text.isBlank()) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            setup(chatId) {
                val chat = chats.getChat(chatId) ?: return@setup
                val path = chats.getActivePath(chatId)
                generate(chat) {
                    val user = chats.addUserMessage(chatId, path.lastOrNull()?.id, text)
                    (path + user) to chats.addAssistantPlaceholder(chatId, user.id, chat.modelId)
                }
            }
        }
        if (!claim(chatId, job)) {
            job.cancel()
            _events.tryEmit(EngineEvent.Error(BUSY_MESSAGE, chatId))
            return
        }
        job.start()
    }

    override fun editAndResend(userMessageId: String, newText: String) {
        if (newText.isBlank()) return
        scope.launch {
            setup(null) { ctx ->
                val old = chats.getMessage(userMessageId) ?: return@setup
                ctx.chatId = old.chatId
                if (!claimCurrent(old.chatId)) return@setup
                val chat = chats.getChat(old.chatId) ?: return@setup
                generate(chat) {
                    val created = chats.editUserAsBranch(userMessageId, newText)
                    val history = pathUpTo(old.chatId, created)
                    history to chats.addAssistantPlaceholder(old.chatId, created.id, chat.modelId)
                }
            }
        }
    }

    override fun regenerate(assistantMessageId: String) {
        scope.launch {
            setup(null) { ctx ->
                val old = chats.getMessage(assistantMessageId) ?: return@setup
                ctx.chatId = old.chatId
                val parentId = old.parentId ?: return@setup
                if (!claimCurrent(old.chatId)) return@setup
                val chat = chats.getChat(old.chatId) ?: return@setup
                val parent = chats.getMessage(parentId) ?: return@setup
                val history = pathUpTo(old.chatId, parent)
                generate(chat) { history to chats.addAssistantSibling(assistantMessageId, chat.modelId) }
            }
        }
    }

    override fun stop(chatId: String) {
        val job = synchronized(lock) { jobs[chatId] }
        job?.cancel()
    }

    // ------------------------------------------------------------------ une génération par chat

    private fun claim(chatId: String, job: Job): Boolean {
        synchronized(lock) {
            if (jobs.containsKey(chatId)) return false
            jobs[chatId] = job
            _generating.update { it + chatId }
        }
        job.invokeOnCompletion { release(chatId, job) }
        return true
    }

    private fun release(chatId: String, job: Job) {
        synchronized(lock) {
            if (jobs[chatId] === job) {
                jobs.remove(chatId)
                _generating.update { it - chatId }
            }
        }
    }

    /** Pour editAndResend/regenerate: le chat n'est connu qu'une fois le message lu. */
    private suspend fun claimCurrent(chatId: String): Boolean {
        val ok = claim(chatId, currentCoroutineContext().job)
        if (!ok) _events.tryEmit(EngineEvent.Error(BUSY_MESSAGE, chatId))
        return ok
    }

    /** Chat concerné par une préparation, renseigné dès qu'il est connu (pour étiqueter les erreurs). */
    private class SetupContext(var chatId: String?)

    /**
     * Attend que l'app soit prête puis exécute la préparation; une erreur d'accès DB est remontée en événement
     * (étiqueté par chat quand il est connu). L'annulation est propagée.
     */
    private suspend fun setup(chatId: String?, block: suspend (SetupContext) -> Unit) {
        val ctx = SetupContext(chatId)
        try {
            awaitReady()
            block(ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _events.tryEmit(EngineEvent.Error("Erreur interne: ${e.message ?: e::class.java.simpleName}", ctx.chatId))
        }
    }

    /**
     * Crée les messages (utilisateur éventuel + placeholder STREAMING) sous [NonCancellable], puis lance [stream].
     * Un Stop qui arrive pendant la création ne laisse donc jamais un message STREAMING sans finalisation:
     * si l'annulation survient entre le commit et le retour, [stream] finalise le placeholder en INTERRUPTED.
     */
    private suspend fun generate(chat: Chat, create: suspend () -> Pair<List<Message>, Message>) {
        var created: Pair<List<Message>, Message>? = null
        try {
            withContext(NonCancellable) { created = create() }
        } catch (e: CancellationException) {
            if (created == null) throw e
        }
        val (history, placeholder) = created ?: return
        stream(chat, history, placeholder)
    }

    /** Chemin actif racine -> [target] inclus (repli sur la remontée des parents si [target] n'est pas sur le chemin actif). */
    private suspend fun pathUpTo(chatId: String, target: Message): List<Message> {
        val active = chats.getActivePath(chatId)
        val idx = active.indexOfFirst { it.id == target.id }
        if (idx >= 0) return active.subList(0, idx + 1)
        val out = ArrayDeque<Message>()
        var cur: Message? = target
        while (cur != null) {
            out.addFirst(cur)
            cur = cur.parentId?.let { chats.getMessage(it) }
        }
        return out.toList()
    }

    // ------------------------------------------------------------------ requête

    /** Paramètres GLOBAUX (Réglages) passés par l'appelant; `chat.params` n'est plus la source de vérité. */
    internal fun buildRequest(chat: Chat, history: List<Message>, globalParams: GenParams): ChatRequest {
        val messages = ArrayList<ApiMessage>()
        chat.systemPromptText?.takeIf { it.isNotBlank() }?.let { messages += ApiMessage("system", it) }
        history.forEach { m ->
            if (m.content.isNotEmpty()) {
                messages += ApiMessage(if (m.role == Role.USER) "user" else "assistant", m.content)
            }
        }
        return ChatRequest(model = chat.modelId, messages = messages, params = GenParamsCompat.forModel(globalParams, chat.modelId))
    }

    // ------------------------------------------------------------------ streaming

    private class Buffer {
        private val content = StringBuilder()
        private val reasoning = StringBuilder()
        private var version = 0L
        private var published = -1L

        @Synchronized
        fun appendContent(s: String) { content.append(s); version++ }

        @Synchronized
        fun appendReasoning(s: String) { reasoning.append(s); version++ }

        @Synchronized
        fun snapshot(): StreamingText = StreamingText(content.toString(), reasoning.toString())

        /** Retourne un snapshot s'il y a du nouveau depuis le dernier appel. */
        @Synchronized
        fun takeIfDirty(): StreamingText? {
            if (version == published) return null
            published = version
            return StreamingText(content.toString(), reasoning.toString())
        }
    }

    private suspend fun stream(chat: Chat, history: List<Message>, placeholder: Message) {
        val id = placeholder.id
        val buffer = Buffer()
        var usage: StreamEvent.Usage? = null
        var finishReason: String? = null
        var failure: Throwable? = null
        var cancelled: CancellationException? = null

        _streaming.update { it + (id to StreamingText("", "")) }
        try {
            currentCoroutineContext().ensureActive()
            val key = settings.apiKey()?.takeIf { it.isNotBlank() }
                ?: throw FireworksException.Unauthorized("Clé API manquante")
            val globalParams = try {
                settings.settings.first().defaultParams
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppDefaults.GEN_PARAMS
            }
            val request = buildRequest(chat, history, globalParams)
            coroutineScope {
                val ticker = launch {
                    var sinceCheckpoint = 0L
                    while (true) {
                        delay(publishIntervalMs)
                        sinceCheckpoint += publishIntervalMs
                        val snap = buffer.takeIfDirty()
                        if (snap != null) _streaming.update { it + (id to snap) }
                        if (snap != null && sinceCheckpoint >= checkpointIntervalMs) {
                            sinceCheckpoint = 0
                            try {
                                chats.updateStreaming(id, snap.content, snap.reasoning.ifEmpty { null })
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // Un checkpoint raté n'interrompt pas la génération.
                            }
                        }
                    }
                }
                try {
                    api.streamChat(key, request).collect { ev ->
                        when (ev) {
                            is StreamEvent.ReasoningDelta -> buffer.appendReasoning(ev.text)
                            is StreamEvent.ContentDelta -> buffer.appendContent(ev.text)
                            is StreamEvent.Usage -> usage = ev
                            is StreamEvent.Finish -> finishReason = ev.reason
                        }
                    }
                } finally {
                    ticker.cancel()
                }
            }
        } catch (e: CancellationException) {
            cancelled = e
        } catch (e: Throwable) {
            failure = e
        }

        val failed = failure
        val interrupted = cancelled
        val usageFinal = usage
        val finishFinal = finishReason
        withContext(NonCancellable) {
            val final = buffer.snapshot()
            var status = MessageStatus.COMPLETE
            var error: String? = null
            val fwFailure = failed as? FireworksException
            when {
                interrupted != null -> status = MessageStatus.INTERRUPTED
                failed != null -> {
                    status = MessageStatus.ERROR
                    error = fwFailure?.toUserMessage()
                        ?: "Erreur inattendue: ${failed.message ?: failed::class.java.simpleName}"
                }
                final.content.isEmpty() && finishFinal == "length" -> {
                    status = MessageStatus.ERROR
                    error = "Coupé pendant la réflexion: augmente les tokens max."
                }
                final.content.isEmpty() -> {
                    status = MessageStatus.ERROR
                    error = "Réponse vide du modèle."
                }
            }
            // Deux écritures indépendantes: si la 1re échoue, le statut final est quand même tenté.
            var saveError: Exception? = null
            try {
                chats.updateStreaming(id, final.content, final.reasoning.ifEmpty { null })
            } catch (e: Exception) {
                saveError = e
            }
            try {
                chats.finishMessage(
                    messageId = id,
                    status = status,
                    finishReason = finishFinal,
                    error = error,
                    promptTokens = usageFinal?.promptTokens,
                    completionTokens = usageFinal?.completionTokens,
                    reasoningTokens = usageFinal?.reasoningTokens,
                )
            } catch (e: Exception) {
                saveError = saveError ?: e
            }
            saveError?.let {
                _events.tryEmit(
                    EngineEvent.Error(
                        "Impossible d'enregistrer la réponse: ${it.message ?: it::class.java.simpleName}", chat.id,
                    ),
                )
            }
            _streaming.update { it - id }

            if (failed != null) {
                if (fwFailure is FireworksException.Unauthorized) {
                    _events.tryEmit(EngineEvent.Unauthorized)
                } else {
                    _events.tryEmit(EngineEvent.Error(error ?: "Erreur inattendue", chat.id))
                }
                if (fwFailure is FireworksException.ModelNotFound) refreshModelsInBackground()
            } else if (status == MessageStatus.ERROR) {
                _events.tryEmit(EngineEvent.Error(error ?: "Erreur inattendue", chat.id))
            }
        }
        interrupted?.let { throw it }
    }

    private fun refreshModelsInBackground() {
        scope.launch {
            try {
                models.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Sans effet: l'utilisateur pourra relancer le rafraîchissement.
            }
        }
    }

    private companion object {
        const val BUSY_MESSAGE = "Une réponse est déjà en cours dans cette conversation."
    }
}
