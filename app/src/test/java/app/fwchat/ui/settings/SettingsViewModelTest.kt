package app.fwchat.ui.settings

import app.cash.turbine.test
import app.fwchat.domain.Chat
import app.fwchat.domain.ChatRepository
import app.fwchat.domain.ChatSummary
import app.fwchat.domain.FireworksException
import app.fwchat.domain.GenParams
import app.fwchat.domain.Message
import app.fwchat.domain.MessageStatus
import app.fwchat.domain.ModelInfo
import app.fwchat.domain.ModelRepository
import app.fwchat.domain.SystemPrompt
import app.fwchat.domain.ThreadItem
import app.fwchat.ui.FakeModelRepo
import app.fwchat.ui.FakePromptRepo
import app.fwchat.ui.FakeSettingsRepo
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
    override suspend fun refresh(): Result<Unit> = throw error
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
        models: ModelRepository = FakeModelRepo(listOf(model("glm-5p3-flash"), model("kimi-k3"))),
        prompts: List<SystemPrompt> = emptyList(),
    ): Env {
        val settings = FakeSettingsRepo(key = key, defaultModel = defaultModel, defaultPrompt = defaultPrompt)
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
        assertEquals("••••1234", s.keyHint)
        assertEquals(e.settings.current.defaultParams, s.params)
    }

    @Test
    fun `remplacement de cle reussi enregistre la nouvelle cle et recharge les modeles`() = runTest(dispatcher) {
        val e = env()
        advanceUntilIdle()
        e.vm.events.test {
            e.vm.replaceKey("  fw_nouvelle_cle_9876  ")
            advanceUntilIdle()
            assertEquals(SettingsEvent.KeyReplaced, awaitItem())
            assertTrue(awaitItem() is SettingsEvent.Message)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("fw_nouvelle_cle_9876", e.settings.key)
        assertEquals(1, (e.models as FakeModelRepo).refreshCount)
        val s = e.vm.state.value
        assertEquals("••••9876", s.keyHint)
        assertFalse(s.validatingKey)
        assertNull(s.keyError)
    }

    @Test
    fun `remplacement de cle reussi choisit un modele par defaut si absent`() = runTest(dispatcher) {
        val e = env(defaultModel = null)
        advanceUntilIdle()
        e.vm.replaceKey("fw_nouvelle_cle_9876")
        advanceUntilIdle()
        assertEquals("accounts/fireworks/models/glm-5p3-flash", e.settings.current.defaultModelId)
    }

    @Test
    fun `remplacement de cle refuse restaure l ancienne cle et affiche l erreur`() = runTest(dispatcher) {
        val repo = FakeModelRepo(failure = FireworksException.Unauthorized("401"))
        val e = env(models = repo)
        advanceUntilIdle()
        e.vm.replaceKey("fw_mauvaise_cle")
        advanceUntilIdle()
        assertEquals("fw_ancienne_cle_1234", e.settings.key)
        assertTrue(e.settings.current.hasApiKey)
        val s = e.vm.state.value
        assertEquals(describeNetworkError(FireworksException.Unauthorized("x")), s.keyError)
        assertFalse(s.validatingKey)
        assertEquals("••••1234", s.keyHint)
        e.vm.events.test { expectNoEvents() }
    }

    @Test
    fun `remplacement de cle avec erreur reseau restaure l ancienne cle`() = runTest(dispatcher) {
        val repo = FakeModelRepo(failure = FireworksException.Network(IOException("hors ligne")))
        val e = env(models = repo)
        advanceUntilIdle()
        e.vm.replaceKey("fw_autre_cle_0000")
        advanceUntilIdle()
        assertEquals("fw_ancienne_cle_1234", e.settings.key)
        assertEquals(describeNetworkError(IOException()), e.vm.state.value.keyError)
    }

    @Test
    fun `remplacement de cle avec exception levee restaure l ancienne cle`() = runTest(dispatcher) {
        val e = env(models = ThrowingModelRepo(IllegalStateException("boom")))
        advanceUntilIdle()
        e.vm.replaceKey("fw_autre_cle_0000")
        advanceUntilIdle()
        assertEquals("fw_ancienne_cle_1234", e.settings.key)
        assertNotNull(e.vm.state.value.keyError)
        assertFalse(e.vm.state.value.validatingKey)
    }

    @Test
    fun `remplacement avec une cle vide ne touche a rien`() = runTest(dispatcher) {
        val e = env()
        advanceUntilIdle()
        e.vm.replaceKey("   ")
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
            e.vm.removeKey()
            advanceUntilIdle()
            assertEquals(SettingsEvent.ApiKeyRemoved, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(e.settings.key)
        assertFalse(e.vm.state.value.hasApiKey)
        assertNull(e.vm.state.value.keyHint)
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
    fun `promptLabel gere aucun prompt nom et prompt supprime`() {
        val prompts = listOf(systemPrompt("p1", "Coach"))
        assertEquals(NO_SYSTEM_PROMPT_LABEL, promptLabel(null, prompts))
        assertEquals("Coach", promptLabel("p1", prompts))
        assertEquals("Prompt introuvable", promptLabel("disparu", prompts))
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
