package app.fwchat.ui

import app.fwchat.domain.AppDefaults
import app.fwchat.domain.AppSettings
import app.fwchat.domain.Chat
import app.fwchat.domain.ChatEngine
import app.fwchat.domain.ChatRepository
import app.fwchat.domain.ChatSummary
import app.fwchat.domain.EngineEvent
import app.fwchat.domain.GenParams
import app.fwchat.domain.Message
import app.fwchat.domain.MessageStatus
import app.fwchat.domain.PlanInfo
import app.fwchat.domain.Provider
import app.fwchat.domain.ModelInfo
import app.fwchat.domain.ModelRepository
import app.fwchat.domain.Role
import app.fwchat.domain.SettingsRepository
import app.fwchat.domain.StreamingText
import app.fwchat.domain.SystemPrompt
import app.fwchat.domain.SystemPromptRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

fun summary(id: String, title: String = id, updatedAt: Long = 0, count: Int = 2, model: String = "accounts/fireworks/models/glm-5p3") =
    ChatSummary(id, title, model, updatedAt, count)

fun flModel(
    id: String, gated: Boolean = false, availableOnPlan: Boolean? = true, concurrencyCost: Int? = 1,
    context: Int = 32768, vision: Boolean = false,
) = ModelInfo(
    id = id, displayName = id.substringAfterLast('/'), contextLength = context, supportsImageInput = vision,
    supportsTools = false, gated = gated, availableOnPlan = availableOnPlan, concurrencyCost = concurrencyCost,
)

fun model(id: String) = ModelInfo(
    id = "accounts/fireworks/models/$id", displayName = id, contextLength = 1000,
    supportsImageInput = false, supportsTools = false,
)

fun systemPrompt(id: String, name: String = id, text: String = "texte") =
    SystemPrompt(id, name, text, family = null, createdAt = 1, updatedAt = 1)

class FakeSettingsRepo(
    key: String? = null,
    defaultModel: String? = null,
    defaultPrompt: String? = null,
) : SettingsRepository {
    private val keys = mutableMapOf<Provider, String?>(Provider.FIREWORKS to key)
    private val state = MutableStateFlow(
        AppSettings(
            key != null, defaultModel, defaultPrompt, AppDefaults.GEN_PARAMS,
            providersWithKey = if (key != null) setOf(Provider.FIREWORKS) else emptySet(),
            defaultModelIds = mapOf(Provider.FIREWORKS to defaultModel),
        ),
    )
    override val settings: Flow<AppSettings> = state
    val current: AppSettings get() = state.value
    /** Fournisseur actif (flux) pour piloter un [FakeModelRepo.follow]. */
    val activeFlow: Flow<Provider> = state.map { it.activeProvider }

    /** Clé Fireworks (compatibilité avec les tests existants). */
    var key: String?
        get() = keys[Provider.FIREWORKS]
        set(v) { keys[Provider.FIREWORKS] = v; publish() }
    fun keyOf(provider: Provider): String? = keys[provider]

    /** Journal des appels `setActiveProvider` acceptés. */
    val activations = mutableListOf<Provider>()

    private fun publish() {
        val withKey = Provider.entries.filter { keys[it] != null }.toSet()
        val s = state.value
        val active = when {
            s.activeProvider in withKey || withKey.isEmpty() -> s.activeProvider
            else -> withKey.first()
        }
        state.value = s.copy(
            hasApiKey = withKey.isNotEmpty(), providersWithKey = withKey, activeProvider = active,
            defaultModelId = s.defaultModelIds[active],
        )
    }

    override suspend fun apiKey(provider: Provider): String? = keys[provider]
    /** Même sémantique que le vrai dépôt: la clé devient active si l'actif n'en a pas; effacer l'actif bascule. */
    override suspend fun setApiKey(provider: Provider, key: String?) {
        keys[provider] = key
        val s = state.value
        if (key != null && keys[s.activeProvider] == null) {
            state.value = s.copy(activeProvider = provider)
        }
        publish()
    }
    override suspend fun setActiveProvider(provider: Provider) {
        val hasAny = Provider.entries.any { keys[it] != null }
        if (keys[provider] == null && hasAny) return
        activations += provider
        val s = state.value
        state.value = s.copy(activeProvider = provider, defaultModelId = s.defaultModelIds[provider])
    }
    override suspend fun setDefaultModel(provider: Provider, modelId: String?) {
        val s = state.value
        val ids = s.defaultModelIds + (provider to modelId)
        state.value = s.copy(defaultModelIds = ids, defaultModelId = ids[s.activeProvider])
    }
    override suspend fun setDefaultSystemPrompt(promptId: String?) {
        state.value = state.value.copy(defaultSystemPromptId = promptId)
    }
    override suspend fun setDefaultParams(params: GenParams) { state.value = state.value.copy(defaultParams = params) }
}

class FakeModelRepo(
    /** Liste Fireworks chargée par `refresh(FIREWORKS)`. */
    var list: List<ModelInfo> = emptyList(),
    /** Résultat des prochains refresh; null = succès. */
    var failure: Throwable? = null,
) : ModelRepository {
    private val states = Provider.entries.associateWith { MutableStateFlow<List<ModelInfo>>(emptyList()) }
    /** Listes chargées par `refresh(p)` pour chaque fournisseur (Fireworks = [list]). */
    val lists = mutableMapOf<Provider, List<ModelInfo>>()
    val failures = mutableMapOf<Provider, Throwable?>()
    var refreshCount = 0
    val refreshed = mutableListOf<Provider>()
    private var activeFlow: Flow<Provider> = flowOf(Provider.FIREWORKS)
    private var activeNow: () -> Provider = { Provider.FIREWORKS }
    val planState = MutableStateFlow<PlanInfo?>(null)
    var planFailure: Throwable? = null
    var planRefreshCount = 0

    /** Suit le fournisseur actif d'un [FakeSettingsRepo] (à appeler avant de construire le ViewModel). */
    fun follow(settings: FakeSettingsRepo): FakeModelRepo {
        activeFlow = settings.activeFlow
        activeNow = { settings.current.activeProvider }
        return this
    }

    /** Remplit directement la liste d'un fournisseur (comme un cache déjà chargé). */
    fun preload(provider: Provider, models: List<ModelInfo>) {
        states.getValue(provider).value = models
        lists[provider] = models
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val models: Flow<List<ModelInfo>>
        get() = activeFlow.distinctUntilChanged().flatMapLatest { states.getValue(it) }
    override fun modelsFor(provider: Provider): Flow<List<ModelInfo>> = states.getValue(provider)
    override val plan: Flow<PlanInfo?> get() = planState
    override suspend fun refreshPlan(): Result<PlanInfo?> {
        planRefreshCount++
        planFailure?.let { return Result.failure(it) }
        return Result.success(planState.value)
    }
    override suspend fun refreshIfStale(provider: Provider, maxAgeMs: Long): Result<Unit> = refresh(provider)
    override suspend fun refresh(provider: Provider?): Result<Unit> {
        val p = provider ?: activeNow()
        refreshCount++
        refreshed += p
        (failures[p] ?: failure)?.let { return Result.failure(it) }
        val loaded = if (p == Provider.FIREWORKS) lists[p] ?: list else lists[p] ?: emptyList()
        states.getValue(p).value = loaded
        return Result.success(Unit)
    }
}

class FakePromptRepo(initial: List<SystemPrompt> = emptyList()) : SystemPromptRepository {
    val items = MutableStateFlow(initial)
    var nextId = 1
    override fun observeAll(): Flow<List<SystemPrompt>> = items
    override suspend fun get(id: String): SystemPrompt? = items.value.firstOrNull { it.id == id }
    override suspend fun upsert(prompt: SystemPrompt): String {
        val id = prompt.id.ifBlank { "new${nextId++}" }
        val saved = prompt.copy(id = id)
        items.value = items.value.filter { it.id != id } + saved
        return id
    }
    override suspend fun delete(id: String) { items.value = items.value.filter { it.id != id } }
}

class FakeChatEngine : ChatEngine {
    override val streaming: StateFlow<Map<String, StreamingText>> = MutableStateFlow(emptyMap())
    val generating = MutableStateFlow<Set<String>>(emptySet())
    override val generatingChats: StateFlow<Set<String>> = generating
    override val events: SharedFlow<EngineEvent> = MutableSharedFlow()
    val stopped = mutableListOf<String>()
    override fun send(chatId: String, text: String) = Unit
    override fun editAndResend(userMessageId: String, newText: String) = Unit
    override fun regenerate(assistantMessageId: String) = Unit
    override fun stop(chatId: String) { stopped += chatId }
}

/** Seules les méthodes utilisées par le tiroir sont implémentées. */
class FakeChatRepoForDrawer(initial: List<ChatSummary> = emptyList()) : ChatRepository {
    val summaries = MutableStateFlow(initial)
    val queries = mutableListOf<String>()
    val renamed = mutableListOf<Pair<String, String>>()
    val deleted = mutableListOf<String>()
    val forks = mutableListOf<Pair<String, String>>()
    var activePath: List<Message> = emptyList()

    override fun observeChatSummaries(query: String): Flow<List<ChatSummary>> {
        queries += query
        return summaries.map { list -> list.filter { it.title.contains(query, ignoreCase = true) } }
    }
    override suspend fun renameChat(chatId: String, title: String) { renamed += chatId to title }
    override suspend fun deleteChat(chatId: String) { deleted += chatId }
    override suspend fun getActivePath(chatId: String): List<Message> = activePath
    override suspend fun fork(chatId: String, uptoMessageId: String): String {
        forks += chatId to uptoMessageId
        return "fork-of-$chatId"
    }

    override fun observeChat(chatId: String): Flow<Chat?> = TODO()
    override fun observeThread(chatId: String) = TODO()
    override suspend fun getChat(chatId: String): Chat? = TODO()
    override suspend fun getMessage(messageId: String): Message? = TODO()
    override suspend fun createChat(modelId: String, systemPrompt: SystemPrompt?, params: GenParams): String = TODO()
    override suspend fun updateChatSettings(chatId: String, modelId: String?, params: GenParams?) = TODO()
    override suspend fun addUserMessage(chatId: String, parentId: String?, content: String): Message = TODO()
    override suspend fun addAssistantPlaceholder(chatId: String, parentId: String, modelId: String): Message = TODO()
    override suspend fun updateStreaming(messageId: String, content: String, reasoning: String?) = TODO()
    override suspend fun finishMessage(
        messageId: String, status: MessageStatus, finishReason: String?, error: String?,
        promptTokens: Int?, completionTokens: Int?, reasoningTokens: Int?,
    ) = TODO()
    override suspend fun editInPlace(messageId: String, content: String) = TODO()
    override suspend fun editUserAsBranch(messageId: String, newContent: String): Message = TODO()
    override suspend fun addAssistantSibling(messageId: String, modelId: String): Message = TODO()
    override suspend fun selectSibling(messageId: String) = TODO()
    override suspend fun deleteSubtree(messageId: String) = TODO()
    override suspend fun recoverInterrupted() = TODO()
    override suspend fun exportAll(): String = TODO()
    override suspend fun importAll(json: String, replace: Boolean) = TODO()
}

fun message(id: String, chatId: String = "c") = Message(
    id = id, chatId = chatId, parentId = null, role = Role.USER, content = "x", reasoning = null,
    selectedChildId = null, createdAt = 0, updatedAt = 0, modelId = null, status = MessageStatus.COMPLETE,
    finishReason = null, error = null, edited = false, promptTokens = null, completionTokens = null,
    reasoningTokens = null,
)
