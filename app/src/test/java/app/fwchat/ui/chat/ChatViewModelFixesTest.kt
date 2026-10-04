package app.fwchat.ui.chat

import app.cash.turbine.test
import app.fwchat.domain.Chat
import app.fwchat.domain.EngineEvent
import app.fwchat.domain.GenParams
import app.fwchat.domain.MessageStatus
import app.fwchat.domain.Role
import app.fwchat.ui.FakeModelRepo
import app.fwchat.ui.FakePromptRepo
import app.fwchat.ui.FakeSettingsRepo
import app.fwchat.ui.model
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelFixesTest {

    private val dispatcher = StandardTestDispatcher()
    private val log = mutableListOf<String>()
    private val repo = MemChatRepo(log)
    private val engine = RecordingEngine(log)
    private val prompts = FakePromptRepo()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private suspend fun models(vararg ids: String) = FakeModelRepo(ids.map { model(it) }).also { it.refresh() }

    private fun TestScope.vm(chatId: String?, models: FakeModelRepo) = ChatViewModel(
        chats = repo,
        prompts = prompts,
        settings = FakeSettingsRepo(defaultModel = "accounts/fireworks/models/glm-5p3"),
        models = models,
        engine = engine,
        chatId = chatId,
        appScope = CoroutineScope(dispatcher + SupervisorJob()),
    )

    private fun existingChat(): String {
        val id = "chat-x"
        repo.chats[id] = Chat(id, "Titre", "accounts/fireworks/models/glm-5p3", null, null, GenParams(), null, 1, 1)
        return id
    }

    // ------------------------------------------------------------------ 6. brouillon non perdu

    @Test
    fun brouillonCreateChatEchoueAucunOpenChatEtLeBoutonRedevientActif() = runTest(dispatcher) {
        val vm = vm(null, models("glm-5p3"))
        advanceUntilIdle()
        repo.createFailure = IllegalStateException("disque plein")

        vm.events.test {
            assertTrue(vm.send("Mon long brouillon"))
            // pour un brouillon l'ecran ne vide pas le champ a ce stade
            assertFalse(clearsDraftImmediately(isDraftChat = vm.state.value.isDraft, accepted = true))
            advanceUntilIdle()
            val ev = awaitItem() as ChatUiEvent.Notice
            assertTrue(ev.message.contains("Impossible de créer le chat"))
            expectNoEvents()
        }
        assertTrue(engine.sent.isEmpty())
        assertTrue(vm.state.value.canSend)

        // et un nouvel essai fonctionne
        repo.createFailure = null
        vm.events.test {
            assertTrue(vm.send("Mon long brouillon"))
            advanceUntilIdle()
            assertEquals(ChatUiEvent.OpenChat("c1", fromDraft = true), awaitItem())
        }
    }

    // ------------------------------------------------------------------ 2. pas de reconstruction du fil

    @Test
    fun checkpointDeLaBaseNeRemplacePasLeFilTantQueLeMessageStreame() = runTest(dispatcher) {
        val id = existingChat()
        val u = repo.addMessage(id, null, Role.USER, "Question")
        val a = repo.addMessage(id, u.id, Role.ASSISTANT, "", MessageStatus.STREAMING)
        val vm = vm(id, models("glm-5p3"))
        advanceUntilIdle()
        val before = vm.state.value.thread

        repo.modify(a.id) { it.copy(content = "Un debut de reponse", reasoning = "reflexion", updatedAt = 5000) }
        advanceUntilIdle()
        assertSame("meme liste", before, vm.state.value.thread)

        repo.modify(a.id) { it.copy(content = "Un debut de reponse plus long", updatedAt = 6000) }
        advanceUntilIdle()
        assertSame(before, vm.state.value.thread)

        // la fin du streaming, elle, est visible
        repo.modify(a.id) { it.copy(content = "Reponse finale", status = MessageStatus.COMPLETE) }
        advanceUntilIdle()
        val after = vm.state.value.thread
        assertEquals("Reponse finale", after.last().message.content)
        assertSame("l'item inchange est reutilise", before[0], after[0])
    }

    // ------------------------------------------------------------------ 8. erreurs par chat

    @Test
    fun seulesLesErreursDuChatVisibleOuGlobalesSontAffichees() = runTest(dispatcher) {
        val id = existingChat()
        val vm = vm(id, models("glm-5p3"))
        advanceUntilIdle()
        vm.engineNotices.test {
            engine.events.emit(EngineEvent.Error("autre chat", chatId = "ailleurs"))
            engine.events.emit(EngineEvent.Error("ce chat", chatId = id))
            assertEquals(EngineNotice.Message("ce chat"), awaitItem())
            engine.events.emit(EngineEvent.Error("global"))
            assertEquals(EngineNotice.Message("global"), awaitItem())
            engine.events.emit(EngineEvent.Unauthorized)
            assertEquals(EngineNotice.Unauthorized, awaitItem())
            expectNoEvents()
        }
    }

    // ------------------------------------------------------------------ 9. exceptions de lecture

    @Test
    fun exceptionSurLeFilNePlantePasEtSignaleUneErreur() = runTest(dispatcher) {
        val id = existingChat()
        repo.threadFailure = IllegalStateException("Row too big to fit into CursorWindow")
        val vm = vm(id, models("glm-5p3"))
        advanceUntilIdle()
        val s = vm.state.value
        assertTrue(s.loadError)
        assertTrue(s.loaded)
        assertFalse(s.notFound)
        assertFalse(s.canSend)
        assertTrue(s.thread.isEmpty())
    }

    @Test
    fun exceptionSurLeChatNePlantePasEtSignaleUneErreur() = runTest(dispatcher) {
        val id = existingChat()
        repo.chatFailure = IllegalStateException("boom")
        val vm = vm(id, models("glm-5p3"))
        advanceUntilIdle()
        assertTrue(vm.state.value.loadError)
        assertFalse(vm.state.value.notFound)
    }

    // ------------------------------------------------------------------ 7. Reessayer

    @Test
    fun reessayerSupprimeLErreurPrecedenteApresLaNouvelleGenerationReussie() = runTest(dispatcher) {
        val id = existingChat()
        val u = repo.addMessage(id, null, Role.USER, "Question")
        val err = repo.addMessage(id, u.id, Role.ASSISTANT, "", MessageStatus.ERROR)
        val vm = vm(id, models("glm-5p3"))
        runCurrent()

        vm.retry(err.id)
        runCurrent()
        assertEquals(listOf(err.id), engine.regenerated)
        assertTrue("rien n'est supprime avant la fin", repo.deletedSubtrees.isEmpty())

        // le moteur cree le frere (STREAMING, selectionne)
        val sibling = repo.addAssistantSibling(err.id, "accounts/fireworks/models/glm-5p3")
        runCurrent()
        assertTrue(repo.deletedSubtrees.isEmpty())

        repo.modify(sibling.id) { it.copy(content = "Voila", status = MessageStatus.COMPLETE) }
        runCurrent()
        assertEquals(listOf(err.id), repo.deletedSubtrees)
        assertEquals(1, vm.state.value.thread.last().siblingCount)
    }

    @Test
    fun reessayerNeSupprimePasUnMessageEnErreurAvecContenuUtile() = runTest(dispatcher) {
        val id = existingChat()
        val u = repo.addMessage(id, null, Role.USER, "Question")
        val err = repo.addMessage(id, u.id, Role.ASSISTANT, "Debut utile", MessageStatus.ERROR)
        val vm = vm(id, models("glm-5p3"))
        runCurrent()
        vm.retry(err.id)
        val sibling = repo.addAssistantSibling(err.id, "accounts/fireworks/models/glm-5p3")
        repo.modify(sibling.id) { it.copy(content = "Voila", status = MessageStatus.COMPLETE) }
        runCurrent()
        assertTrue(repo.deletedSubtrees.isEmpty())
    }

    @Test
    fun reessayerGardeLesDeuxSiLaNouvelleGenerationEstInterrompue() = runTest(dispatcher) {
        val id = existingChat()
        val u = repo.addMessage(id, null, Role.USER, "Question")
        val err = repo.addMessage(id, u.id, Role.ASSISTANT, "", MessageStatus.ERROR)
        val vm = vm(id, models("glm-5p3"))
        runCurrent()
        vm.retry(err.id)
        val sibling = repo.addAssistantSibling(err.id, "accounts/fireworks/models/glm-5p3")
        repo.modify(sibling.id) { it.copy(content = "Partiel", status = MessageStatus.INTERRUPTED) }
        runCurrent()
        assertTrue(repo.deletedSubtrees.isEmpty())
    }
}
