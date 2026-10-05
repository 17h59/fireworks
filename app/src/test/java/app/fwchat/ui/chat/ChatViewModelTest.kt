package app.fwchat.ui.chat

import app.cash.turbine.test
import app.fwchat.domain.AppDefaults
import app.fwchat.domain.GenParams
import app.fwchat.domain.ReasoningEffort
import app.fwchat.domain.Role
import app.fwchat.ui.FakeModelRepo
import app.fwchat.ui.FakePromptRepo
import app.fwchat.ui.FakeSettingsRepo
import app.fwchat.ui.model
import app.fwchat.ui.prompts.resolvePlaceholders
import app.fwchat.ui.systemPrompt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val log = mutableListOf<String>()
    private val repo = MemChatRepo(log)
    private val engine = RecordingEngine(log)
    private val prompts = FakePromptRepo(
        listOf(systemPrompt("p1", "Mon prompt", "Nous sommes le {{date}}."), systemPrompt("p2", "Autre", "Autre texte")),
    )
    private val fixedNow = ZonedDateTime.of(2026, 3, 5, 14, 30, 0, 0, ZoneId.of("Europe/Paris"))

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private suspend fun models(vararg ids: String) = FakeModelRepo(ids.map { model(it) }).also { it.refresh() }

    private fun TestScope.vm(
        chatId: String?,
        settings: FakeSettingsRepo,
        models: FakeModelRepo,
    ) = ChatViewModel(
        chats = repo,
        prompts = prompts,
        settings = settings,
        models = models,
        engine = engine,
        chatId = chatId,
        appScope = CoroutineScope(dispatcher + SupervisorJob()),
        paramsDebounceMs = 400,
        clock = { fixedNow },
    )

    private fun existingChat(prompt: String? = null): String {
        val id = "chat-x"
        repo.chats[id] = app.fwchat.domain.Chat(
            id, "Titre", "accounts/fireworks/models/glm-5p3", prompt?.let { "Nom" }, prompt,
            GenParams(maxTokens = 100), null, 1, 1,
        )
        return id
    }

    // ------------------------------------------------------------------ brouillon

    @Test
    fun brouillon_prechargeModeleEtPromptParDefaut() = runTest(dispatcher) {
        val settings = FakeSettingsRepo(defaultModel = "accounts/fireworks/models/kimi-k3", defaultPrompt = "p2")
        val vm = vm(null, settings, models("glm-5p3", "kimi-k3"))
        advanceUntilIdle()
        val s = vm.state.value
        assertTrue(s.isDraft)
        assertTrue(s.loaded)
        assertEquals("accounts/fireworks/models/kimi-k3", s.modelId)
        assertEquals("p2", s.selectedPromptId)
        assertEquals("Autre", s.promptName)
        assertFalse(s.promptLocked)
        assertEquals(settings.current.defaultParams, s.params)
        assertTrue(s.canSend)
    }

    @Test
    fun brouillon_sansModeleParDefautPrendLePremierDeLaListe() = runTest(dispatcher) {
        val vm = vm(null, FakeSettingsRepo(), models("glm-5p3", "kimi-k3"))
        advanceUntilIdle()
        assertEquals("accounts/fireworks/models/glm-5p3", vm.state.value.modelId)
        assertNull(vm.state.value.promptName)
    }

    @Test
    fun brouillon_premierEnvoiCreeLeChatAvecSnapshotResoluPuisEnvoiePuisNavigue() = runTest(dispatcher) {
        val settings = FakeSettingsRepo(defaultModel = "accounts/fireworks/models/glm-5p3", defaultPrompt = "p1")
        val vm = vm(null, settings, models("glm-5p3"))
        advanceUntilIdle()
        vm.setParams(GenParams(temperature = 0.3, maxTokens = 512))

        vm.events.test {
            assertTrue(vm.send("Bonjour"))
            advanceUntilIdle()
            assertEquals(ChatUiEvent.OpenChat("c1", fromDraft = true), awaitItem())
        }

        val created = repo.created.single()
        assertEquals("accounts/fireworks/models/glm-5p3", created.modelId)
        assertEquals(GenParams(temperature = 0.3, maxTokens = 512), created.params)
        val snapshot = created.prompt!!
        assertEquals(resolvePlaceholders("Nous sommes le {{date}}.", fixedNow), snapshot.text)
        assertFalse(snapshot.text.contains("{{"))
        // la bibliotheque n'est jamais modifiee
        assertEquals("Nous sommes le {{date}}.", prompts.get("p1")!!.text)
        assertEquals(listOf("c1" to "Bonjour"), engine.sent)
        assertEquals(listOf("create", "send"), log)
    }

    @Test
    fun brouillon_pastillesModifiablesAvantLEnvoi() = runTest(dispatcher) {
        val vm = vm(null, FakeSettingsRepo(defaultPrompt = "p1"), models("glm-5p3", "kimi-k3"))
        advanceUntilIdle()
        vm.setModel("accounts/fireworks/models/kimi-k3")
        vm.setPrompt("p2")
        advanceUntilIdle()
        assertEquals("p2", vm.state.value.selectedPromptId)
        vm.setPrompt(null)
        advanceUntilIdle()
        assertNull(vm.state.value.promptName)

        vm.send("Salut")
        advanceUntilIdle()
        val created = repo.created.single()
        assertEquals("accounts/fireworks/models/kimi-k3", created.modelId)
        assertNull(created.prompt)
        assertTrue(repo.settingsCalls.isEmpty())
    }

    @Test
    fun brouillon_messageVideRefuseEtDoubleEnvoiIgnore() = runTest(dispatcher) {
        val vm = vm(null, FakeSettingsRepo(), models("glm-5p3"))
        advanceUntilIdle()
        assertFalse(vm.send("   "))
        assertTrue(vm.send("a"))
        assertFalse(vm.send("b"))
        advanceUntilIdle()
        assertEquals(1, repo.created.size)
        assertEquals(1, engine.sent.size)
    }

    // ------------------------------------------------------------------ prompt verrouille

    @Test
    fun chatExistant_promptVerrouilleEtNonModifiable() = runTest(dispatcher) {
        val id = existingChat(prompt = "Texte fige")
        val vm = vm(id, FakeSettingsRepo(defaultPrompt = "p1"), models("glm-5p3"))
        advanceUntilIdle()
        val before = vm.state.value
        assertTrue(before.promptLocked)
        assertEquals("Nom", before.promptName)
        assertEquals("Texte fige", before.promptText)

        vm.setPrompt("p2")
        advanceUntilIdle()
        assertEquals(before.promptText, vm.state.value.promptText)
        assertEquals("Nom", vm.state.value.promptName)
        assertEquals("Texte fige", repo.chats.getValue(id).systemPromptText)
    }

    @Test
    fun chatExistant_sansPromptAfficheAucunPromptEtVerrouille() = runTest(dispatcher) {
        val id = existingChat(prompt = null)
        val vm = vm(id, FakeSettingsRepo(), models("glm-5p3"))
        advanceUntilIdle()
        assertTrue(vm.state.value.promptLocked)
        assertNull(vm.state.value.promptName)
        assertNull(vm.state.value.promptText)
    }

    @Test
    fun chatExistant_modeleToujoursModifiable() = runTest(dispatcher) {
        val id = existingChat()
        val vm = vm(id, FakeSettingsRepo(), models("glm-5p3", "kimi-k3"))
        advanceUntilIdle()
        vm.setModel("accounts/fireworks/models/kimi-k3")
        advanceUntilIdle()
        assertEquals(MemChatRepo.SettingsCall(id, "accounts/fireworks/models/kimi-k3", null), repo.settingsCalls.single())
        assertEquals("accounts/fireworks/models/kimi-k3", vm.state.value.modelId)
    }

    @Test
    fun chatExistant_modeleAbsentDeLaListeEstSignale() = runTest(dispatcher) {
        val id = existingChat()
        val vm = vm(id, FakeSettingsRepo(), models("kimi-k3"))
        advanceUntilIdle()
        assertFalse(vm.state.value.modelAvailable)
    }

    // ------------------------------------------------------------------ envoi / editions

    private fun chatWithExchange(): Triple<String, String, String> {
        val id = existingChat()
        val u = repo.addMessage(id, null, Role.USER, "Question")
        val a = repo.addMessage(id, u.id, Role.ASSISTANT, "Reponse")
        return Triple(id, u.id, a.id)
    }

    @Test
    fun envoiDansChatExistantPasseParLeMoteur() = runTest(dispatcher) {
        val (id, _, _) = chatWithExchange()
        val vm = vm(id, FakeSettingsRepo(), models("glm-5p3"))
        advanceUntilIdle()
        assertTrue(vm.send("Suite"))
        assertEquals(listOf(id to "Suite"), engine.sent)
        assertTrue(repo.created.isEmpty())
    }

    @Test
    fun modifierEtEnvoyerUtiliseEditAndResendSansToucherAuContenu() = runTest(dispatcher) {
        val (id, u, _) = chatWithExchange()
        val vm = vm(id, FakeSettingsRepo(), models("glm-5p3"))
        advanceUntilIdle()
        vm.startEdit(u)
        advanceUntilIdle()
        assertEquals(u, vm.state.value.editingMessageId)

        vm.sendEdit(u, "Question corrigee")
        advanceUntilIdle()
        assertEquals(listOf(u to "Question corrigee"), engine.editResends)
        assertTrue(repo.inPlaceEdits.isEmpty())
        assertNull(vm.state.value.editingMessageId)
    }

    @Test
    fun enregistrerUtiliseEditInPlaceSansRegenerer() = runTest(dispatcher) {
        val (id, u, a) = chatWithExchange()
        val vm = vm(id, FakeSettingsRepo(), models("glm-5p3"))
        advanceUntilIdle()
        vm.startEdit(a)
        vm.saveEdit(a, "Reponse corrigee")
        advanceUntilIdle()
        assertEquals(listOf(a to "Reponse corrigee"), repo.inPlaceEdits)
        assertTrue(engine.editResends.isEmpty())
        assertTrue(engine.sent.isEmpty())
        assertTrue(repo.branchEdits.isEmpty())
        assertNull(vm.state.value.editingMessageId)
        assertTrue(vm.state.value.thread.first { it.message.id == a }.message.edited)
        // le message utilisateur est inchange
        assertEquals("Question", vm.state.value.thread.first { it.message.id == u }.message.content)
    }

    @Test
    fun annulerEditionNeFaitRien() = runTest(dispatcher) {
        val (id, u, _) = chatWithExchange()
        val vm = vm(id, FakeSettingsRepo(), models("glm-5p3"))
        advanceUntilIdle()
        vm.startEdit(u)
        vm.cancelEdit()
        advanceUntilIdle()
        assertNull(vm.state.value.editingMessageId)
        assertTrue(repo.inPlaceEdits.isEmpty() && engine.editResends.isEmpty())
    }

    @Test
    fun actionsBloqueesPendantUneGeneration() = runTest(dispatcher) {
        val (id, u, a) = chatWithExchange()
        val vm = vm(id, FakeSettingsRepo(), models("glm-5p3"))
        advanceUntilIdle()
        engine.generating.value = setOf(id)
        advanceUntilIdle()
        assertTrue(vm.state.value.generating)
        assertFalse(vm.state.value.canSend)

        vm.startEdit(u)
        vm.regenerate(a)
        vm.sendEdit(u, "x")
        vm.deleteMessage(a)
        assertFalse(vm.send("encore"))
        advanceUntilIdle()
        assertNull(vm.state.value.editingMessageId)
        assertTrue(engine.regenerated.isEmpty() && engine.editResends.isEmpty() && engine.sent.isEmpty())
        assertTrue(repo.deletedSubtrees.isEmpty())

        vm.stop()
        assertEquals(listOf(id), engine.stopped)
    }

    @Test
    fun regenererPasseParLeMoteur() = runTest(dispatcher) {
        val (id, _, a) = chatWithExchange()
        val vm = vm(id, FakeSettingsRepo(), models("glm-5p3"))
        advanceUntilIdle()
        vm.regenerate(a)
        assertEquals(listOf(a), engine.regenerated)
    }

    @Test
    fun supprimerUnMessageSupprimeLeSousArbre() = runTest(dispatcher) {
        val (id, u, a) = chatWithExchange()
        val vm = vm(id, FakeSettingsRepo(), models("glm-5p3"))
        advanceUntilIdle()
        vm.deleteMessage(u)
        advanceUntilIdle()
        assertEquals(listOf(u), repo.deletedSubtrees)
        assertTrue(vm.state.value.thread.isEmpty())
        assertTrue(repo.msgs.none { it.id == a })
    }

    // ------------------------------------------------------------------ fork

    @Test
    fun forkEmetOpenChatAvecLeNouvelId() = runTest(dispatcher) {
        val (id, _, a) = chatWithExchange()
        val vm = vm(id, FakeSettingsRepo(), models("glm-5p3"))
        advanceUntilIdle()
        vm.events.test {
            vm.fork(a)
            advanceUntilIdle()
            assertEquals(ChatUiEvent.OpenChat("fork-of-$id"), awaitItem())
        }
        assertEquals(listOf(id to a), repo.forks)
    }

    // ------------------------------------------------------------------ branches

    @Test
    fun flechesDeBranchesSelectionnentLeFrere() = runTest(dispatcher) {
        val (id, u, a) = chatWithExchange()
        val a2 = repo.addMessage(id, u, Role.ASSISTANT, "Variante") // frere, selectionne
        val vm = vm(id, FakeSettingsRepo(), models("glm-5p3"))
        advanceUntilIdle()
        val item = vm.state.value.thread.last()
        assertEquals(a2.id, item.message.id)
        assertEquals(2, item.siblingCount)
        assertEquals(1, item.siblingIndex)

        vm.selectSibling(item, -1)
        advanceUntilIdle()
        val after = vm.state.value.thread.last()
        assertEquals(a, after.message.id)
        assertEquals(0, after.siblingIndex)
    }

    // ------------------------------------------------------------------ parametres

    @Test
    fun parametresDeChatSontAppliquesAvecDebounce() = runTest(dispatcher) {
        val id = existingChat()
        val settings = FakeSettingsRepo()
        val vm = vm(id, settings, models("glm-5p3"))
        advanceUntilIdle()

        val p1 = GenParams(maxTokens = 100, temperature = 0.1)
        val p2 = GenParams(maxTokens = 100, temperature = 0.2)
        val p3 = GenParams(maxTokens = 100, temperature = 0.3, reasoningEffort = ReasoningEffort.LOW)
        vm.setParams(p1)
        runCurrent()
        advanceTimeBy(100)
        vm.setParams(p2)
        runCurrent()
        advanceTimeBy(100)
        vm.setParams(p3)
        runCurrent()
        // l'affichage suit immediatement
        assertEquals(p3, vm.state.value.params)
        advanceTimeBy(399)
        runCurrent()
        assertEquals(AppDefaults.GEN_PARAMS, settings.current.defaultParams)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(p3, settings.current.defaultParams)
        assertTrue(repo.settingsCalls.isEmpty()) // plus d'ecriture par chat
        advanceUntilIdle()
        assertEquals(p3, vm.state.value.params)
    }

    @Test
    fun modifierDepuisUnChatMetAJourLeGlobalVuParUnAutreChatEtUnBrouillon() = runTest(dispatcher) {
        val id = existingChat()
        val settings = FakeSettingsRepo()
        val vm = vm(id, settings, models("glm-5p3"))
        val other = vm(null, settings, models("glm-5p3"))
        advanceUntilIdle()
        val p = GenParams(maxTokens = 99, temperature = 0.4, stop = listOf("FIN"))
        vm.setParams(p)
        vm.flushParams()
        advanceUntilIdle()
        assertEquals(p, settings.current.defaultParams)
        assertEquals(p, other.state.value.params)
        assertEquals(p, vm.state.value.params)
    }

    @Test
    fun changerDeModeleNeModifiePasLesParametres() = runTest(dispatcher) {
        val id = existingChat()
        val settings = FakeSettingsRepo()
        val vm = vm(id, settings, models("glm-5p3", "kimi-k3"))
        advanceUntilIdle()
        val p = GenParams(maxTokens = 123, reasoningEffort = ReasoningEffort.NONE)
        vm.setParams(p)
        vm.flushParams()
        advanceUntilIdle()
        vm.setModel("accounts/fireworks/models/kimi-k3")
        advanceUntilIdle()
        assertEquals(p, settings.current.defaultParams)
        assertEquals(p, vm.state.value.params)
    }

    @Test
    fun flushParamsEnregistreImmediatement() = runTest(dispatcher) {
        val id = existingChat()
        val settings = FakeSettingsRepo()
        val vm = vm(id, settings, models("glm-5p3"))
        advanceUntilIdle()
        val p = GenParams(maxTokens = 100, topP = 0.5)
        vm.setParams(p)
        runCurrent()
        vm.flushParams()
        runCurrent()
        assertEquals(p, settings.current.defaultParams)
        assertTrue(repo.settingsCalls.isEmpty())
    }

    @Test
    fun parametresDuBrouillonSontGlobaux() = runTest(dispatcher) {
        val settings = FakeSettingsRepo()
        val vm = vm(null, settings, models("glm-5p3"))
        advanceUntilIdle()
        val p = GenParams(temperature = 1.5)
        vm.setParams(p)
        advanceTimeBy(1000)
        advanceUntilIdle()
        assertEquals(p, vm.state.value.params)
        assertEquals(p, settings.current.defaultParams)
        assertTrue(repo.chats.isEmpty())
    }

    // ------------------------------------------------------------------ divers

    @Test
    fun renommerAppelleLeDepot() = runTest(dispatcher) {
        val id = existingChat()
        val vm = vm(id, FakeSettingsRepo(), models("glm-5p3"))
        advanceUntilIdle()
        vm.rename("  Mon titre ")
        advanceUntilIdle()
        assertEquals(listOf(id to "Mon titre"), repo.renames)
        assertEquals("Mon titre", vm.state.value.title)
    }

    @Test
    fun copierLaConversationEmetUnTexteFormate() = runTest(dispatcher) {
        val (id, _, _) = chatWithExchange()
        val vm = vm(id, FakeSettingsRepo(), models("glm-5p3"))
        advanceUntilIdle()
        vm.events.test {
            vm.copyConversation()
            val ev = awaitItem() as ChatUiEvent.CopyText
            assertTrue(ev.confirmation.startsWith("Conversation"))
            assertTrue(ev.text.contains("Toi :\nQuestion"))
            assertTrue(ev.text.contains("Reponse"))
        }
    }

    @Test
    fun chatSupprimeEstSignale() = runTest(dispatcher) {
        val vm = vm("inconnu", FakeSettingsRepo(), models("glm-5p3"))
        advanceUntilIdle()
        assertTrue(vm.state.value.notFound)
        assertNotNull(vm.state.value)
    }
}
