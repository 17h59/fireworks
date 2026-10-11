package app.fwchat.ui.settings

import app.cash.turbine.test
import app.fwchat.data.net.toUserMessage
import app.fwchat.domain.Chat
import app.fwchat.domain.ChatRepository
import app.fwchat.domain.ChatSummary
import app.fwchat.domain.FireworksException
import app.fwchat.domain.GenParams
import app.fwchat.domain.Message
import app.fwchat.domain.MessageStatus
import app.fwchat.domain.ModelInfo
import app.fwchat.domain.ModelRepository
import app.fwchat.domain.PlanInfo
import app.fwchat.domain.Provider
import app.fwchat.domain.SystemPrompt
import app.fwchat.domain.ThreadItem
import app.fwchat.ui.FakeModelRepo
import app.fwchat.ui.FakePromptRepo
import app.fwchat.ui.FakeSettingsRepo
import app.fwchat.ui.flModel
import app.fwchat.ui.model
import app.fwchat.ui.systemPrompt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** Fake de sauvegarde: seules exportAll/importAll sont utilisées par l'écran de réglages. */
private class FakeBackupRepo(
    var exportJson: String = "{\"format\":\"fwchat-backup\"}",
    var importFailure: Throwable? = null,
) : ChatRepository {
    val imports = mutableListOf<Pair<String, Boolean>>()
    override suspend fun exportAll(): String = exportJson
    override suspend fun importAll(json: String, replace: Boolean) {
        importFailure?.let { throw it }
        imports += json to replace
    }

    override fun observeChatSummaries(query: String): Flow<List<ChatSummary>> = TODO()
    override fun observeChat(chatId: String): Flow<Chat?> = TODO()
    override fun observeThread(chatId: String): Flow<List<ThreadItem>> = TODO()
    override suspend fun getChat(chatId: String): Chat? = TODO()
    override suspend fun getActivePath(chatId: String): List<Message> = TODO()
    override suspend fun getMessage(messageId: String): Message? = TODO()
    override suspend fun createChat(modelId: String, systemPrompt: SystemPrompt?, params: GenParams): String = TODO()
    override suspend fun renameChat(chatId: String, title: String) = TODO()
    override suspend fun deleteChat(chatId: String) = TODO()
    override suspend fun updateChatSettings(chatId: String, modelId: String?, params: GenParams?) = TODO()
    override suspend fun fork(chatId: String, uptoMessageId: String): String = TODO()
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
}

/** refresh() qui lève une exception au lieu de renvoyer un Result.failure. */
private class ThrowingModelRepo(private val error: Exception) : ModelRepository {
    override val models: Flow<List<ModelInfo>> = MutableStateFlow(emptyList())
    override fun modelsFor(provider: app.fwchat.domain.Provider): Flow<List<ModelInfo>> = models
    override val plan: Flow<app.fwchat.domain.PlanInfo?> = MutableStateFlow(null)
    override suspend fun refreshPlan(): Result<app.fwchat.domain.PlanInfo?> = throw error
    override suspend fun refreshIfStale(provider: app.fwchat.domain.Provider, maxAgeMs: Long): Result<Unit> = throw error
    override suspend fun refresh(provider: app.fwchat.domain.Provider?): Result<Unit> = throw error
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class Env(
        val settings: FakeSettingsRepo,
        val models: ModelRepository,
        val prompts: FakePromptRepo,
        val backup: FakeBackupRepo,
        val vm: SettingsViewModel,
    )

    private fun TestScope.env(
        key: String? = "fw_ancienne_cle_1234",
        defaultModel: String? = null,
        defaultPrompt: String? = null,
        models: ModelRepository? = null,
        prompts: List<SystemPrompt> = emptyList(),
    ): Env {
        val settings = FakeSettingsRepo(key = key, defaultModel = defaultModel, defaultPrompt = defaultPrompt)
        val models = models ?: FakeModelRepo(listOf(model("glm-5p3-flash"), model("kimi-k3"))).follow(settings)
        val promptRepo = FakePromptRepo(prompts)
        val backup = FakeBackupRepo()
        val vm = SettingsViewModel(settings, models, promptRepo, backup, CoroutineScope(dispatcher))
        // Garde l'état "chaud" (stateIn WhileSubscribed).
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
        return Env(settings, models, promptRepo, backup, vm)
    }

    @Test
    fun `etat initial charge les parametres et le motif de la cle`() = runTest(dispatcher) {
        val e = env()
        advanceUntilIdle()
        val s = e.vm.state.value
        assertTrue(s.loaded)
        assertTrue(s.hasApiKey)
        assertEquals("••••1234", s.keyHints[Provider.FIREWORKS])
        assertEquals(e.settings.current.defaultParams, s.params)
    }

    @Test
    fun `remplacement de cle reussi enregistre la nouvelle cle et recharge les modeles`() = runTest(dispatcher) {
        val e = env()
        advanceUntilIdle()
        e.vm.events.test {
            e.vm.replaceKey(Provider.FIREWORKS, "  fw_nouvelle_cle_9876  ")
            advanceUntilIdle()
            assertEquals(SettingsEvent.KeyReplaced, awaitItem())
            assertTrue(awaitItem() is SettingsEvent.Message)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("fw_nouvelle_cle_9876", e.settings.key)
        assertEquals(1, (e.models as FakeModelRepo).refreshCount)
        val s = e.vm.state.value
        assertEquals("••••9876", s.keyHints[Provider.FIREWORKS])
        assertFalse(s.validatingKey)
        assertNull(s.keyError)
    }

    @Test
    fun `remplacement de cle reussi choisit un modele par defaut si absent`() = runTest(dispatcher) {
        val e = env(defaultModel = null)
        advanceUntilIdle()
        e.vm.replaceKey(Provider.FIREWORKS, "fw_nouvelle_cle_9876")
        advanceUntilIdle()
        assertEquals("accounts/fireworks/models/glm-5p3-flash", e.settings.current.defaultModelId)
    }

    @Test
    fun `remplacement de cle refuse restaure l ancienne cle et affiche l erreur`() = runTest(dispatcher) {
        val repo = FakeModelRepo(failure = FireworksException.Unauthorized("401"))
        val e = env(models = repo)
        assertTrue(describeNetworkError(FireworksException.Unauthorized("x")).contains("Fireworks"))
        advanceUntilIdle()
        e.vm.replaceKey(Provider.FIREWORKS, "fw_mauvaise_cle")
        advanceUntilIdle()
        assertEquals("fw_ancienne_cle_1234", e.settings.key)
        assertTrue(e.settings.current.hasApiKey)
        val s = e.vm.state.value
        assertEquals(describeNetworkError(FireworksException.Unauthorized("x")), s.keyError)
        assertFalse(s.validatingKey)
        assertEquals("••••1234", s.keyHints[Provider.FIREWORKS])
        e.vm.events.test { expectNoEvents() }
    }

    @Test
    fun `remplacement de cle avec erreur reseau restaure l ancienne cle`() = runTest(dispatcher) {
        val repo = FakeModelRepo(failure = FireworksException.Network(IOException("hors ligne")))
        val e = env(models = repo)
        advanceUntilIdle()
        e.vm.replaceKey(Provider.FIREWORKS, "fw_autre_cle_0000")
        advanceUntilIdle()
        assertEquals("fw_ancienne_cle_1234", e.settings.key)
        assertEquals(describeNetworkError(IOException()), e.vm.state.value.keyError)
    }

    @Test
    fun `remplacement de cle avec exception levee restaure l ancienne cle`() = runTest(dispatcher) {
        val e = env(models = ThrowingModelRepo(IllegalStateException("boom")))
        advanceUntilIdle()
        e.vm.replaceKey(Provider.FIREWORKS, "fw_autre_cle_0000")
        advanceUntilIdle()
        assertEquals("fw_ancienne_cle_1234", e.settings.key)
        assertNotNull(e.vm.state.value.keyError)
        assertFalse(e.vm.state.value.validatingKey)
    }

    @Test
    fun `remplacement avec une cle vide ne touche a rien`() = runTest(dispatcher) {
        val e = env()
        advanceUntilIdle()
        e.vm.replaceKey(Provider.FIREWORKS, "   ")
        advanceUntilIdle()
        assertNotNull(e.vm.state.value.keyError)
        assertEquals("fw_ancienne_cle_1234", e.settings.key)
        assertEquals(0, (e.models as FakeModelRepo).refreshCount)
        e.vm.clearKeyError()
        advanceUntilIdle()
        assertNull(e.vm.state.value.keyError)
    }

    @Test
    fun `suppression de la cle l efface et demande le retour a l onboarding`() = runTest(dispatcher) {
        val e = env()
        advanceUntilIdle()
        e.vm.events.test {
            e.vm.removeKey(Provider.FIREWORKS)
            advanceUntilIdle()
            assertEquals(SettingsEvent.ApiKeyRemoved, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(e.settings.key)
        assertFalse(e.vm.state.value.hasApiKey)
        assertNull(e.vm.state.value.keyHints[Provider.FIREWORKS])
    }

    // ------------------------------------------------------------------ deux fournisseurs

    private val fw = Provider.FIREWORKS
    private val fl = Provider.FEATHERLESS

    /** Environnement avec une clé Fireworks ET une clé Featherless (Fireworks actif). */
    private suspend fun TestScope.twoKeys(): Env {
        val e = env()
        advanceUntilIdle()
        e.settings.setApiKey(fl, "fl_cle_featherless_5678")
        val repo = e.models as FakeModelRepo
        repo.lists[fl] = listOf(flModel("Qwen/Qwen3-8B"), flModel("aaa/gros", concurrencyCost = 4))
        repo.planState.value = PlanInfo("Premium", 32768, 4, null)
        advanceUntilIdle()
        return e
    }

    @Test
    fun `etat expose le mode actif les cles et les indices des deux fournisseurs`() = runTest(dispatcher) {
        val e = twoKeys()
        val s = e.vm.state.value
        assertEquals(fw, s.activeProvider)
        assertEquals(setOf(fw, fl), s.providersWithKey)
        assertEquals("••••1234", s.keyHints[fw])
        assertEquals("••••5678", s.keyHints[fl])
    }

    @Test
    fun `changer de mode bascule le fournisseur actif et la liste de modeles`() = runTest(dispatcher) {
        val e = twoKeys()
        e.vm.selectProvider(fl)
        advanceUntilIdle()
        assertEquals(fl, e.settings.current.activeProvider)
        assertEquals(fl, e.vm.state.value.activeProvider)
        e.vm.refreshModels()
        advanceUntilIdle()
        assertEquals(listOf("Qwen/Qwen3-8B", "aaa/gros"), e.vm.state.value.models.map { it.id })
        e.vm.selectProvider(fw)
        advanceUntilIdle()
        assertEquals(fw, e.vm.state.value.activeProvider)
    }

    @Test
    fun `choisir un fournisseur sans cle ne change pas de mode`() = runTest(dispatcher) {
        val e = env()
        advanceUntilIdle()
        e.vm.selectProvider(fl)
        advanceUntilIdle()
        assertEquals(fw, e.settings.current.activeProvider)
        assertTrue(e.settings.activations.isEmpty())
    }

    @Test
    fun `le plan Featherless est charge a l entree en mode Featherless`() = runTest(dispatcher) {
        val e = twoKeys()
        val repo = e.models as FakeModelRepo
        assertEquals(0, repo.planRefreshCount)
        e.vm.selectProvider(fl)
        advanceUntilIdle()
        assertEquals(1, repo.planRefreshCount)
        val s = e.vm.state.value
        assertEquals("Premium", s.plan?.name)
        assertFalse(s.planLoading)
        assertNull(s.planError)
    }

    @Test
    fun `erreur de plan est lisible et rechargeable`() = runTest(dispatcher) {
        val e = twoKeys()
        val repo = e.models as FakeModelRepo
        repo.planFailure = FireworksException.Network(IOException("off"))
        e.vm.selectProvider(fl)
        advanceUntilIdle()
        assertEquals(describeNetworkError(IOException(), fl), e.vm.state.value.planError)
        assertTrue(e.vm.state.value.planError!!.contains("Featherless"))
        repo.planFailure = null
        e.vm.loadPlan()
        advanceUntilIdle()
        assertNull(e.vm.state.value.planError)
        assertEquals(2, repo.planRefreshCount)
    }

    @Test
    fun `ajout d une cle Featherless valide l active et choisit un modele`() = runTest(dispatcher) {
        val e = env()
        advanceUntilIdle()
        val repo = e.models as FakeModelRepo
        repo.lists[fl] = listOf(flModel("Qwen/Qwen3-8B"))
        e.vm.events.test {
            e.vm.replaceKey(fl, "  fl_nouvelle_cle_0001  ")
            advanceUntilIdle()
            assertEquals(SettingsEvent.KeyReplaced, awaitItem())
            val msg = awaitItem() as SettingsEvent.Message
            assertEquals("Clé Featherless enregistrée : Featherless l'a acceptée. Mode Featherless activé.", msg.text)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("fl_nouvelle_cle_0001", e.settings.keyOf(fl))
        assertEquals("fw_ancienne_cle_1234", e.settings.key)
        assertEquals(fl, e.settings.current.activeProvider)
        assertEquals("Qwen/Qwen3-8B", e.settings.current.defaultModelIds[fl])
        assertEquals("••••0001".length, e.vm.state.value.keyHints[fl]!!.length)
    }

    @Test
    fun `ajout d une cle Featherless refusee nettoie sans toucher a Fireworks`() = runTest(dispatcher) {
        val e = env()
        advanceUntilIdle()
        (e.models as FakeModelRepo).failures[fl] = FireworksException.Unauthorized("401")
        e.vm.replaceKey(fl, "fl_mauvaise")
        advanceUntilIdle()
        assertNull(e.settings.keyOf(fl))
        assertEquals("fw_ancienne_cle_1234", e.settings.key)
        assertEquals(fw, e.settings.current.activeProvider)
        val s = e.vm.state.value
        assertEquals(fl, s.keyProvider)
        assertEquals(describeNetworkError(FireworksException.Unauthorized("x"), fl), s.keyError)
        assertTrue(s.keyError!!.contains("Featherless"))
        assertFalse(s.validatingKey)
    }

    @Test
    fun `remplacement refuse de la cle Featherless restaure l ancienne`() = runTest(dispatcher) {
        val e = twoKeys()
        (e.models as FakeModelRepo).failures[fl] = FireworksException.Unauthorized("401")
        e.vm.replaceKey(fl, "fl_mauvaise")
        advanceUntilIdle()
        assertEquals("fl_cle_featherless_5678", e.settings.keyOf(fl))
        assertEquals(fw, e.settings.current.activeProvider)
    }

    @Test
    fun `supprimer la cle du fournisseur actif bascule sur l autre`() = runTest(dispatcher) {
        val e = twoKeys()
        e.vm.events.test {
            e.vm.removeKey(fw)
            advanceUntilIdle()
            val msg = awaitItem() as SettingsEvent.Message
            assertEquals("Clé Fireworks supprimée. Mode Featherless activé.", msg.text)
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(e.settings.key)
        assertEquals(fl, e.settings.current.activeProvider)
        assertTrue(e.settings.current.hasApiKey)
        assertEquals(setOf(fl), e.vm.state.value.providersWithKey)
    }

    @Test
    fun `supprimer la cle de l autre fournisseur garde le mode actif`() = runTest(dispatcher) {
        val e = twoKeys()
        e.vm.events.test {
            e.vm.removeKey(fl)
            advanceUntilIdle()
            val msg = awaitItem() as SettingsEvent.Message
            assertEquals("Clé Featherless supprimée.", msg.text)
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(e.settings.keyOf(fl))
        assertEquals(fw, e.settings.current.activeProvider)
    }

    @Test
    fun `supprimer la derniere cle renvoie a l onboarding`() = runTest(dispatcher) {
        val e = twoKeys()
        e.vm.removeKey(fl)
        advanceUntilIdle()
        e.vm.events.test {
            assertTrue(awaitItem() is SettingsEvent.Message) // « Clé Featherless supprimée. »
            e.vm.removeKey(fw)
            advanceUntilIdle()
            assertEquals(SettingsEvent.ApiKeyRemoved, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertFalse(e.settings.current.hasApiKey)
    }

    @Test
    fun `le modele par defaut est celui du fournisseur actif`() = runTest(dispatcher) {
        val e = twoKeys()
        e.vm.setDefaultModel("accounts/fireworks/models/kimi-k3")
        advanceUntilIdle()
        e.vm.selectProvider(fl)
        advanceUntilIdle()
        e.vm.setDefaultModel("Qwen/Qwen3-8B")
        advanceUntilIdle()
        assertEquals("accounts/fireworks/models/kimi-k3", e.settings.current.defaultModelIds[fw])
        assertEquals("Qwen/Qwen3-8B", e.settings.current.defaultModelIds[fl])
        assertEquals("Qwen/Qwen3-8B", e.vm.state.value.defaultModelId)
        e.vm.selectProvider(fw)
        advanceUntilIdle()
        assertEquals("accounts/fireworks/models/kimi-k3", e.vm.state.value.defaultModelId)
    }

    @Test
    fun `choisir un modele verrouille par defaut previent`() = runTest(dispatcher) {
        val e = twoKeys()
        (e.models as FakeModelRepo).preload(fl, listOf(flModel("meta/secret", gated = true), flModel("a/b", availableOnPlan = false)))
        e.vm.selectProvider(fl)
        advanceUntilIdle()
        e.vm.events.test {
            e.vm.setDefaultModel("meta/secret")
            advanceUntilIdle()
            assertTrue((awaitItem() as SettingsEvent.Message).text.contains("verrouillé"))
            e.vm.setDefaultModel("a/b")
            advanceUntilIdle()
            assertTrue((awaitItem() as SettingsEvent.Message).text.contains("hors de ton plan"))
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("a/b", e.settings.current.defaultModelId)
    }

    @Test
    fun `le rechargement concerne le fournisseur actif`() = runTest(dispatcher) {
        val e = twoKeys()
        val repo = e.models as FakeModelRepo
        e.vm.selectProvider(fl)
        advanceUntilIdle()
        e.vm.refreshModels()
        advanceUntilIdle()
        assertEquals(listOf(fl), repo.refreshed)
    }

    @Test
    fun `describeNetworkError nomme le bon fournisseur`() {
        assertTrue(describeNetworkError(IOException(), fl).contains("Featherless"))
        assertTrue(describeNetworkError(FireworksException.Http(500, ""), fw).startsWith("Fireworks"))
        assertEquals(
            FireworksException.ModelGated("https://x", "m").toUserMessage(),
            describeNetworkError(FireworksException.ModelGated("https://x", "m"), fl),
        )
        assertTrue(describeNetworkError(FireworksException.RateLimited("c", null, concurrency = true), fl).contains("concurrence"))
    }

    @Test
    fun `resume de plan et etat des cles`() {
        assertEquals(
            "Premium · concurrence 4 · contexte max 32k",
            planSummary(PlanInfo("Premium", 32768, 4, null)),
        )
        assertEquals("Plan Featherless", planSummary(PlanInfo("", null, null, null)))
        assertEquals("Enregistrée ••••1234 · mode actif", keyStatusLabel(true, "••••1234", true))
        assertEquals("Enregistrée ••••1234", keyStatusLabel(true, "••••1234", false))
        assertEquals("Absente", keyStatusLabel(false, null, false))
        assertEquals("Clé Featherless", keyTitle(fl))
        assertEquals("12", formatCredits(12.0))
        assertEquals("12,50", formatCredits(12.5))
    }

    @Test
    fun `consequence de la suppression d une cle`() {
        assertEquals(
            "Le mode Featherless sera activé.",
            removeKeyConsequence(fw, fw, setOf(fw, fl)),
        )
        assertEquals("Le mode Fireworks reste actif.", removeKeyConsequence(fl, fw, setOf(fw, fl)))
        assertTrue(removeKeyConsequence(fw, fw, setOf(fw)).contains("saisir une clé"))
    }

    @Test
    fun `modele et prompt par defaut sont enregistres`() = runTest(dispatcher) {
        val e = env(defaultPrompt = "p1", prompts = listOf(systemPrompt("p1", "Coach")))
        advanceUntilIdle()
        e.vm.setDefaultModel("accounts/fireworks/models/kimi-k3")
        e.vm.setDefaultPrompt(null)
        advanceUntilIdle()
        assertEquals("accounts/fireworks/models/kimi-k3", e.settings.current.defaultModelId)
        assertNull(e.settings.current.defaultSystemPromptId)
        assertNull(e.vm.state.value.defaultPromptId)
        e.vm.setDefaultPrompt("p1")
        advanceUntilIdle()
        assertEquals("p1", e.settings.current.defaultSystemPromptId)
    }

    @Test
    fun `rechargement des modeles annonce le nombre de modeles`() = runTest(dispatcher) {
        val e = env()
        advanceUntilIdle()
        e.vm.events.test {
            e.vm.refreshModels()
            advanceUntilIdle()
            val msg = awaitItem() as SettingsEvent.Message
            assertEquals(modelCountLabel(2) + ".", msg.text)
            cancelAndIgnoreRemainingEvents()
        }
        assertFalse(e.vm.state.value.refreshingModels)
        assertEquals(2, e.vm.state.value.models.size)
    }

    @Test
    fun `rechargement des modeles en erreur affiche un message lisible`() = runTest(dispatcher) {
        val e = env(models = FakeModelRepo(failure = FireworksException.Http(500, "boom")))
        advanceUntilIdle()
        e.vm.events.test {
            e.vm.refreshModels()
            advanceUntilIdle()
            val msg = awaitItem() as SettingsEvent.Message
            assertEquals(describeNetworkError(FireworksException.Http(500, "")), msg.text)
            cancelAndIgnoreRemainingEvents()
        }
        assertFalse(e.vm.state.value.refreshingModels)
    }

    @Test
    fun `parametres par defaut enregistres apres le debounce avec la derniere valeur`() = runTest(dispatcher) {
        val e = env()
        advanceUntilIdle()
        val initial = e.settings.current.defaultParams
        e.vm.onParamsChange(initial.copy(temperature = 0.1))
        runCurrent()
        advanceTimeBy(200)
        e.vm.onParamsChange(initial.copy(temperature = 0.5))
        runCurrent()
        advanceTimeBy(200)
        e.vm.onParamsChange(initial.copy(temperature = 0.9))
        runCurrent()
        // L'etat local suit tout de suite, mais rien n'est encore enregistre.
        assertEquals(0.9, e.vm.state.value.params?.temperature)
        advanceTimeBy(399)
        assertEquals(initial, e.settings.current.defaultParams)
        advanceTimeBy(2)
        assertEquals(0.9, e.settings.current.defaultParams.temperature)
    }

    @Test
    fun `flushParams enregistre tout de suite une modification en attente`() = runTest(dispatcher) {
        val e = env()
        advanceUntilIdle()
        val initial = e.settings.current.defaultParams
        e.vm.onParamsChange(initial.copy(maxTokens = 2048))
        runCurrent()
        assertEquals(initial, e.settings.current.defaultParams)
        e.vm.flushParams()
        runCurrent()
        assertEquals(2048, e.settings.current.defaultParams.maxTokens)
        // Le debounce annule ne reecrit rien d'ancien.
        advanceUntilIdle()
        assertEquals(2048, e.settings.current.defaultParams.maxTokens)
    }

    @Test
    fun `export ecrit le contenu de exportAll et annonce le resultat`() = runTest(dispatcher) {
        val e = env()
        advanceUntilIdle()
        e.backup.exportJson = "{\"test\":1}"
        var written: String? = null
        e.vm.events.test {
            e.vm.export { written = it }
            runCurrent()
            advanceUntilIdle()
            assertTrue(awaitItem() is SettingsEvent.Message)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("{\"test\":1}", written)
        assertNull(e.vm.state.value.backupOp)
    }

    @Test
    fun `export en echec affiche une erreur et libere l indicateur`() = runTest(dispatcher) {
        val e = env()
        advanceUntilIdle()
        e.vm.events.test {
            e.vm.export { throw IOException("disque plein") }
            advanceUntilIdle()
            val msg = awaitItem() as SettingsEvent.Message
            assertTrue(msg.text.contains("export"))
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(e.vm.state.value.backupOp)
    }

    @Test
    fun `import fusionne ou remplace selon le choix`() = runTest(dispatcher) {
        val e = env()
        advanceUntilIdle()
        e.vm.import({ "json-a" }, replace = false)
        advanceUntilIdle()
        e.vm.import({ "json-b" }, replace = true)
        advanceUntilIdle()
        assertEquals(listOf("json-a" to false, "json-b" to true), e.backup.imports)
        assertNull(e.vm.state.value.backupOp)
    }

    @Test
    fun `import invalide affiche un message clair et ne plante pas`() = runTest(dispatcher) {
        val e = env()
        advanceUntilIdle()
        e.backup.importFailure = IllegalArgumentException("Fichier de sauvegarde invalide")
        e.vm.events.test {
            e.vm.import({ "n importe quoi" }, replace = true)
            advanceUntilIdle()
            val msg = awaitItem() as SettingsEvent.Message
            assertTrue(msg.text.contains("sauvegarde FW Chat valide"))
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(e.vm.state.value.backupOp)
        assertTrue(e.backup.imports.isEmpty())
    }

    @Test
    fun `import avec lecture impossible affiche une erreur generique`() = runTest(dispatcher) {
        val e = env()
        advanceUntilIdle()
        e.vm.events.test {
            e.vm.import({ throw IOException("illisible") }, replace = false)
            advanceUntilIdle()
            val msg = awaitItem() as SettingsEvent.Message
            assertTrue(msg.text.contains("import"))
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(e.vm.state.value.backupOp)
    }

    // ------------------------------------------------------------------ logique pure

    @Test
    fun `maskApiKey ne revele au plus que 4 caracteres`() {
        assertNull(maskApiKey(null))
        assertNull(maskApiKey("  "))
        assertEquals("••••", maskApiKey("fw_court"))
        assertEquals("••••wxyz", maskApiKey("fw_abcdefghijklmnopwxyz"))
    }

    @Test
    fun `un prompt par defaut supprime est remis a aucun`() = runTest(dispatcher) {
        val e = env(defaultPrompt = "p1", prompts = listOf(systemPrompt("p1", "Coach")))
        advanceUntilIdle()
        assertEquals("p1", e.settings.current.defaultSystemPromptId)
        assertEquals("p1", e.vm.state.value.defaultPromptId)

        e.prompts.delete("p1")
        advanceUntilIdle()
        assertNull(e.settings.current.defaultSystemPromptId)
        assertNull(e.vm.state.value.defaultPromptId)
    }

    @Test
    fun `un defaut orphelin au demarrage est remis a aucun`() = runTest(dispatcher) {
        val e = env(defaultPrompt = "fantome", prompts = listOf(systemPrompt("p1")))
        advanceUntilIdle()
        assertNull(e.settings.current.defaultSystemPromptId)
        assertNull(e.vm.state.value.defaultPromptId)
    }

    @Test
    fun `promptLabel gere aucun prompt nom et prompt supprime`() {
        val prompts = listOf(systemPrompt("p1", "Coach"))
        assertEquals(NO_SYSTEM_PROMPT_LABEL, promptLabel(null, prompts))
        assertEquals("Coach", promptLabel("p1", prompts))
        assertEquals(NO_SYSTEM_PROMPT_LABEL, promptLabel("disparu", prompts))
        assertTrue(isStaleDefaultPrompt("disparu", prompts))
        assertFalse(isStaleDefaultPrompt("p1", prompts))
        assertFalse(isStaleDefaultPrompt(null, prompts))
    }

    @Test
    fun `modelSummary affiche nom court et contexte`() {
        val m = model("glm-5p3-flash").copy(contextLength = 131072)
        assertEquals("glm-5p3-flash · contexte 128k", modelSummary(m.id, listOf(m)))
        assertEquals("Aucun modèle choisi", modelSummary(null, listOf(m)))
        assertTrue(modelSummary("accounts/fireworks/models/x", listOf(m)).startsWith("x"))
    }

    @Test
    fun `modelCountLabel gere le singulier et le pluriel`() {
        assertEquals("Aucun modèle disponible", modelCountLabel(0))
        assertEquals("1 modèle disponible", modelCountLabel(1))
        assertEquals("13 modèles disponibles", modelCountLabel(13))
    }

    @Test
    fun `backupFileName suit le format demande`() {
        assertEquals("fwchat-sauvegarde-2026-10-04.json", backupFileName(java.time.LocalDate.of(2026, 10, 4)))
    }
}
