package app.fwchat.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.fwchat.data.db.AppDatabase
import app.fwchat.data.db.inMemory
import app.fwchat.domain.GenParams
import app.fwchat.domain.Message
import app.fwchat.domain.MessageStatus
import app.fwchat.domain.PromptFamily
import app.fwchat.domain.ReasoningEffort
import app.fwchat.domain.Role
import app.fwchat.domain.SystemPrompt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: ChatRepositoryImpl
    private lateinit var promptRepo: SystemPromptRepositoryImpl
    private var counter = 0
    private val idGen: () -> String = { "id${++counter}" }

    private val model = "accounts/fireworks/models/glm-5p3"
    private val params = GenParams(temperature = 0.7, stop = listOf("FIN"), reasoningEffort = ReasoningEffort.HIGH)

    @Before
    fun setUp() {
        db = AppDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        // Horloge figée: le repo garantit des horodatages strictement croissants.
        repo = ChatRepositoryImpl(db, clock = { 1_000L }, idGenerator = idGen)
        promptRepo = SystemPromptRepositoryImpl(db, clock = { 5_000L }, idGenerator = idGen)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun prompt(name: String = "Expert", text: String = "Tu es un expert.") =
        SystemPrompt("", name, text, PromptFamily.GLM, 0, 0)

    private suspend fun newChat(sp: SystemPrompt? = null) = repo.createChat(model, sp, params)

    private suspend fun thread(chatId: String) = repo.observeThread(chatId).first()
    private suspend fun threadIds(chatId: String) = thread(chatId).map { it.message.id }

    private suspend fun assistantReply(chatId: String, parent: String, text: String): Message {
        val a = repo.addAssistantPlaceholder(chatId, parent, model)
        repo.updateStreaming(a.id, text, null)
        repo.finishMessage(a.id, MessageStatus.COMPLETE, "stop", null, 10, 5, null)
        return repo.getMessage(a.id)!!
    }

    // ------------------------------------------------------------ création

    @Test
    fun createChatStoresSnapshotAndParams() = runBlocking<Unit> {
        val id = newChat(prompt())
        val chat = repo.getChat(id)!!
        assertEquals("", chat.title)
        assertEquals(model, chat.modelId)
        assertEquals("Expert", chat.systemPromptName)
        assertEquals("Tu es un expert.", chat.systemPromptText)
        assertEquals(params, chat.params)
        assertNull(chat.selectedRootId)
        assertTrue(thread(id).isEmpty())
        assertTrue(repo.getActivePath(id).isEmpty())
    }

    @Test
    fun createChatWithoutPromptHasNullSnapshot() = runBlocking<Unit> {
        val chat = repo.getChat(newChat(null))!!
        assertNull(chat.systemPromptName)
        assertNull(chat.systemPromptText)
    }

    @Test
    fun autoTitleFromFirstLineOnFirstUserMessageOnly() = runBlocking<Unit> {
        val id = newChat()
        val u1 = repo.addUserMessage(id, null, "\nExplique-moi la photosynthèse en détail s'il te plaît, merci beaucoup\nligne 2")
        val title = repo.getChat(id)!!.title
        assertEquals("Explique-moi la photosynthèse en détail" .take(40).trimEnd() + "…", title)
        val a1 = assistantReply(id, u1.id, "ok")
        repo.addUserMessage(id, a1.id, "autre chose")
        assertEquals(title, repo.getChat(id)!!.title)
    }

    @Test
    fun manualRenameIsNotOverwrittenAndEmptyTitleStaysEmptyForBlankText() = runBlocking<Unit> {
        val id = newChat()
        repo.addUserMessage(id, null, "   ")
        assertEquals("", repo.getChat(id)!!.title)
        repo.renameChat(id, "  Mon titre ")
        repo.addUserMessage(id, null, "salut")
        assertEquals("Mon titre", repo.getChat(id)!!.title)
    }

    @Test
    fun addUserMessageRejectsUnknownChatOrForeignParent() {
        runBlocking {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repo.addUserMessage("nope", null, "x") }
            }
            val c1 = newChat()
            val c2 = newChat()
            val m = repo.addUserMessage(c1, null, "a")
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repo.addUserMessage(c2, m.id, "b") }
            }
        }
    }

    // ------------------------------------------------------------ conversation linéaire

    @Test
    fun linearConversationAndStreamingLifecycle() = runBlocking<Unit> {
        val id = newChat()
        val u = repo.addUserMessage(id, null, "Salut")
        val a = repo.addAssistantPlaceholder(id, u.id, model)
        assertEquals(MessageStatus.STREAMING, a.status)
        assertEquals("", a.content)
        assertEquals(model, a.modelId)

        repo.updateStreaming(a.id, "Bon", "réfl")
        repo.updateStreaming(a.id, "Bonjour", "réflexion")
        assertEquals("Bonjour", repo.getMessage(a.id)!!.content)
        assertEquals("réflexion", repo.getMessage(a.id)!!.reasoning)

        repo.finishMessage(a.id, MessageStatus.COMPLETE, "stop", null, 12, 34, 7)
        // Un checkpoint tardif ne doit plus écraser un message terminé.
        repo.updateStreaming(a.id, "ZZZ", null)
        val done = repo.getMessage(a.id)!!
        assertEquals("Bonjour", done.content)
        assertEquals(MessageStatus.COMPLETE, done.status)
        assertEquals("stop", done.finishReason)
        assertEquals(12, done.promptTokens)
        assertEquals(34, done.completionTokens)
        assertEquals(7, done.reasoningTokens)

        assertEquals(listOf(u.id, a.id), threadIds(id))
        assertEquals(listOf(u.id, a.id), repo.getActivePath(id).map { it.id })
        assertEquals(id, repo.getChat(id)!!.id)
        assertEquals(u.id, repo.getChat(id)!!.selectedRootId)
    }

    @Test
    fun finishWithErrorKeepsPartialContent() = runBlocking<Unit> {
        val id = newChat()
        val u = repo.addUserMessage(id, null, "x")
        val a = repo.addAssistantPlaceholder(id, u.id, model)
        repo.updateStreaming(a.id, "partiel", null)
        repo.finishMessage(a.id, MessageStatus.ERROR, null, "429", null, null, null)
        val m = repo.getMessage(a.id)!!
        assertEquals("partiel", m.content)
        assertEquals(MessageStatus.ERROR, m.status)
        assertEquals("429", m.error)
    }

    @Test
    fun observeThreadEmitsOnChanges() = runBlocking<Unit> {
        val id = newChat()
        repo.observeThread(id).test {
            assertTrue(awaitItem().isEmpty())
            val u = repo.addUserMessage(id, null, "Hello")
            assertEquals(listOf(u.id), awaitItem().map { it.message.id })
            val a = repo.addAssistantPlaceholder(id, u.id, model)
            assertEquals(listOf(u.id, a.id), awaitItem().map { it.message.id })
            repo.updateStreaming(a.id, "texte", null)
            assertEquals("texte", awaitItem().last().message.content)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ------------------------------------------------------------ édition / branches

    @Test
    fun editInPlaceChangesTextWithoutBranch() = runBlocking<Unit> {
        val id = newChat()
        val u = repo.addUserMessage(id, null, "avant")
        val a = assistantReply(id, u.id, "réponse")
        repo.editInPlace(u.id, "après")
        repo.editInPlace(a.id, "réponse corrigée")
        val t = thread(id)
        assertEquals(listOf(u.id, a.id), t.map { it.message.id })
        assertEquals("après", t[0].message.content)
        assertTrue(t[0].message.edited)
        assertEquals(1, t[0].siblingCount)
        assertEquals("réponse corrigée", t[1].message.content)
        assertTrue(t[1].message.edited)
        assertFalse(repo.getMessage(u.id)!!.status == MessageStatus.STREAMING)
    }

    @Test
    fun editFirstUserMessageAsBranchCreatesSecondRoot() = runBlocking<Unit> {
        val id = newChat()
        val u1 = repo.addUserMessage(id, null, "question 1")
        val a1 = assistantReply(id, u1.id, "réponse 1")

        val u1b = repo.editUserAsBranch(u1.id, "question 1 bis")
        assertNull(u1b.parentId)
        assertEquals(Role.USER, u1b.role)
        assertFalse(u1b.edited)
        assertEquals(u1b.id, repo.getChat(id)!!.selectedRootId)

        var t = thread(id)
        assertEquals(listOf(u1b.id), t.map { it.message.id })
        assertEquals(listOf(u1.id, u1b.id), t[0].siblingIds)
        assertEquals(1, t[0].siblingIndex)

        val a1b = assistantReply(id, u1b.id, "réponse bis")
        assertEquals(listOf(u1b.id, a1b.id), threadIds(id))

        // Flèche vers l'ancienne branche: son contenu en dessous est retrouvé.
        repo.selectSibling(u1.id)
        assertEquals(listOf(u1.id, a1.id), threadIds(id))
        assertEquals(u1.id, repo.getChat(id)!!.selectedRootId)

        // Et retour: la mémoire de la branche bis est conservée.
        repo.selectSibling(u1b.id)
        assertEquals(listOf(u1b.id, a1b.id), threadIds(id))
        // Le titre n'a pas changé.
        assertEquals("question 1", repo.getChat(id)!!.title)
    }

    @Test
    fun editUserAsBranchOnMiddleMessageKeepsSameParent() = runBlocking<Unit> {
        val id = newChat()
        val u1 = repo.addUserMessage(id, null, "q1")
        val a1 = assistantReply(id, u1.id, "r1")
        val u2 = repo.addUserMessage(id, a1.id, "q2")
        val a2 = assistantReply(id, u2.id, "r2")

        val u2b = repo.editUserAsBranch(u2.id, "q2 bis")
        assertEquals(a1.id, u2b.parentId)
        assertEquals(u2b.id, repo.getMessage(a1.id)!!.selectedChildId)
        var t = thread(id)
        assertEquals(listOf(u1.id, a1.id, u2b.id), t.map { it.message.id })
        assertEquals(2, t[2].siblingCount)

        repo.selectSibling(u2.id)
        t = thread(id)
        assertEquals(listOf(u1.id, a1.id, u2.id, a2.id), t.map { it.message.id })
        assertEquals(0, t[2].siblingIndex)
    }

    @Test
    fun editUserAsBranchRejectsAssistantMessage() = runBlocking<Unit> {
        val id = newChat()
        val u = repo.addUserMessage(id, null, "q")
        val a = assistantReply(id, u.id, "r")
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.editUserAsBranch(a.id, "x") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.addAssistantSibling(u.id, model) } }
    }

    @Test
    fun regenerateCreatesSelectedSiblingAndBranchMemoryBelowNode() = runBlocking<Unit> {
        val id = newChat()
        val u1 = repo.addUserMessage(id, null, "q1")
        val a1 = assistantReply(id, u1.id, "r1")
        val u2 = repo.addUserMessage(id, a1.id, "q2")
        val a2 = assistantReply(id, u2.id, "r2")

        val a1b = repo.addAssistantSibling(a1.id, "autre-modele")
        assertEquals(u1.id, a1b.parentId)
        assertEquals(MessageStatus.STREAMING, a1b.status)
        assertEquals("autre-modele", a1b.modelId)
        var t = thread(id)
        assertEquals(listOf(u1.id, a1b.id), t.map { it.message.id })
        assertEquals(listOf(a1.id, a1b.id), t[1].siblingIds)

        repo.updateStreaming(a1b.id, "r1 bis", null)
        repo.finishMessage(a1b.id, MessageStatus.COMPLETE, "stop", null, null, null, null)

        // Retour sur la première réponse: q2/r2 sont toujours dessous.
        repo.selectSibling(a1.id)
        assertEquals(listOf(u1.id, a1.id, u2.id, a2.id), threadIds(id))
        // Troisième réponse puis navigation.
        val a1c = repo.addAssistantSibling(a1b.id, model)
        t = thread(id)
        assertEquals(3, t[1].siblingCount)
        assertEquals(2, t[1].siblingIndex)
        assertEquals(a1c.id, t[1].message.id)
        repo.selectSibling(a1b.id)
        assertEquals(listOf(u1.id, a1b.id), threadIds(id))
    }

    @Test
    fun newUserMessageUnderAssistantFollowsSelection() = runBlocking<Unit> {
        val id = newChat()
        val u1 = repo.addUserMessage(id, null, "q1")
        val a1 = assistantReply(id, u1.id, "r1")
        val u2 = repo.addUserMessage(id, a1.id, "q2")
        assertEquals(u2.id, repo.getMessage(a1.id)!!.selectedChildId)
        assertEquals(listOf(u1.id, a1.id, u2.id), repo.getActivePath(id).map { it.id })
    }

    // ------------------------------------------------------------ fork

    @Test
    fun forkClonesActivePathUpToMessageOnly() = runBlocking<Unit> {
        val id = newChat(prompt())
        repo.renameChat(id, "Recette")
        val u1 = repo.addUserMessage(id, null, "q1")
        val a1 = assistantReply(id, u1.id, "r1")
        val u2 = repo.addUserMessage(id, a1.id, "q2")
        val a2 = assistantReply(id, u2.id, "r2")
        val u2b = repo.editUserAsBranch(u2.id, "q2 bis") // autre branche, hors chemin cloné
        assistantReply(id, u2b.id, "r2 bis")
        repo.selectSibling(u2.id) // chemin actif: u1, a1, u2, a2

        val forkId = repo.fork(id, a1.id)
        assertNotEquals(id, forkId)
        val fork = repo.getChat(forkId)!!
        assertEquals("Fork de Recette", fork.title)
        assertEquals(model, fork.modelId)
        assertEquals(params, fork.params)
        assertEquals("Expert", fork.systemPromptName)
        assertEquals("Tu es un expert.", fork.systemPromptText)

        val path = repo.getActivePath(forkId)
        assertEquals(listOf("q1", "r1"), path.map { it.content })
        assertTrue(path.all { it.chatId == forkId })
        assertTrue(path.none { it.id in setOf(u1.id, a1.id) })
        assertEquals(fork.selectedRootId, path.first().id)
        assertEquals(listOf(1, 1), thread(forkId).map { it.siblingCount })

        // L'original est intact.
        assertEquals(listOf(u1.id, a1.id, u2.id, a2.id), threadIds(id))
        assertEquals(6, repo.observeChatSummaries("").first().first { it.id == id }.messageCount)
        assertEquals(2, repo.observeChatSummaries("").first().first { it.id == forkId }.messageCount)
    }

    @Test
    fun forkOfUntitledChatAndOfWholeThread() = runBlocking<Unit> {
        val id = newChat(null)
        val u = repo.addUserMessage(id, null, "   ")
        val a = assistantReply(id, u.id, "r")
        val forkId = repo.fork(id, a.id)
        assertEquals("Fork de Nouveau chat", repo.getChat(forkId)!!.title)
        assertNull(repo.getChat(forkId)!!.systemPromptText)
        assertEquals(2, repo.getActivePath(forkId).size)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.fork(id, "inconnu") } }
    }

    @Test
    fun forkOfStreamingMessageIsMarkedInterrupted() = runBlocking<Unit> {
        val id = newChat()
        val u = repo.addUserMessage(id, null, "q")
        val a = repo.addAssistantPlaceholder(id, u.id, model)
        val forkId = repo.fork(id, a.id)
        assertEquals(MessageStatus.INTERRUPTED, repo.getActivePath(forkId).last().status)
        assertEquals(MessageStatus.STREAMING, repo.getMessage(a.id)!!.status)
    }

    // ------------------------------------------------------------ suppression

    @Test
    fun deleteSubtreeRemovesDescendantsAndFallsBackToSibling() = runBlocking<Unit> {
        val id = newChat()
        val u1 = repo.addUserMessage(id, null, "q1")
        val a1 = assistantReply(id, u1.id, "r1")
        val u2 = repo.addUserMessage(id, a1.id, "q2")
        val a2 = assistantReply(id, u2.id, "r2")
        val a1b = repo.addAssistantSibling(a1.id, model) // sélectionnée, frère de a1

        // Supprimer la réponse sélectionnée: retombe sur le frère restant.
        repo.deleteSubtree(a1b.id)
        assertNull(repo.getMessage(a1b.id))
        assertEquals(a1.id, repo.getMessage(u1.id)!!.selectedChildId)
        assertEquals(listOf(u1.id, a1.id, u2.id, a2.id), threadIds(id))

        // Supprimer q2 supprime aussi r2; plus de frère: sélection null.
        repo.deleteSubtree(u2.id)
        assertNull(repo.getMessage(u2.id))
        assertNull(repo.getMessage(a2.id))
        assertNull(repo.getMessage(a1.id)!!.selectedChildId)
        assertEquals(listOf(u1.id, a1.id), threadIds(id))
    }

    @Test
    fun deleteNonSelectedSiblingKeepsSelection() = runBlocking<Unit> {
        val id = newChat()
        val u1 = repo.addUserMessage(id, null, "q1")
        val a1 = assistantReply(id, u1.id, "r1")
        val a1b = repo.addAssistantSibling(a1.id, model)
        val a1c = repo.addAssistantSibling(a1.id, model)
        assertEquals(a1c.id, repo.getMessage(u1.id)!!.selectedChildId)
        repo.deleteSubtree(a1b.id)
        assertEquals(a1c.id, repo.getMessage(u1.id)!!.selectedChildId)
        assertEquals(listOf(a1.id, a1c.id), thread(id)[1].siblingIds)
    }

    @Test
    fun deleteRootFallsBackToOtherRootThenNull() = runBlocking<Unit> {
        val id = newChat()
        val u1 = repo.addUserMessage(id, null, "q1")
        val a1 = assistantReply(id, u1.id, "r1")
        val u1b = repo.editUserAsBranch(u1.id, "q1 bis")
        repo.deleteSubtree(u1b.id)
        assertEquals(u1.id, repo.getChat(id)!!.selectedRootId)
        assertEquals(listOf(u1.id, a1.id), threadIds(id))
        repo.deleteSubtree(u1.id)
        assertNull(repo.getChat(id)!!.selectedRootId)
        assertTrue(thread(id).isEmpty())
        assertNull(repo.getMessage(a1.id))
    }

    @Test
    fun deleteChatCascadesToMessages() = runBlocking<Unit> {
        val id = newChat()
        val u = repo.addUserMessage(id, null, "q")
        val a = assistantReply(id, u.id, "r")
        val other = newChat()
        val ou = repo.addUserMessage(other, null, "autre")
        repo.deleteChat(id)
        assertNull(repo.getChat(id))
        assertNull(repo.getMessage(u.id))
        assertNull(repo.getMessage(a.id))
        assertNotNull(repo.getMessage(ou.id))
        assertEquals(listOf(other), repo.observeChatSummaries("").first().map { it.id })
    }

    // ------------------------------------------------------------ interruptions

    @Test
    fun recoverInterruptedMarksOnlyStreamingMessages() = runBlocking<Unit> {
        val id = newChat()
        val u = repo.addUserMessage(id, null, "q")
        val streaming = repo.addAssistantPlaceholder(id, u.id, model)
        repo.updateStreaming(streaming.id, "début", null)
        val done = repo.addAssistantSibling(streaming.id, model)
        repo.finishMessage(done.id, MessageStatus.COMPLETE, "stop", null, null, null, null)
        repo.recoverInterrupted()
        val s = repo.getMessage(streaming.id)!!
        assertEquals(MessageStatus.INTERRUPTED, s.status)
        assertEquals("début", s.content)
        assertEquals(MessageStatus.COMPLETE, repo.getMessage(done.id)!!.status)
        assertEquals(MessageStatus.COMPLETE, repo.getMessage(u.id)!!.status)
    }

    // ------------------------------------------------------------ réglages

    @Test
    fun updateChatSettingsChangesModelAndParamsButNeverPrompt() = runBlocking<Unit> {
        val id = newChat(prompt())
        repo.updateChatSettings(id, modelId = "accounts/fireworks/models/kimi-k3")
        assertEquals("accounts/fireworks/models/kimi-k3", repo.getChat(id)!!.modelId)
        assertEquals(params, repo.getChat(id)!!.params)

        val p2 = GenParams(maxTokens = 99, topK = 5, seed = 42L)
        repo.updateChatSettings(id, params = p2)
        assertEquals(p2, repo.getChat(id)!!.params)
        assertEquals("accounts/fireworks/models/kimi-k3", repo.getChat(id)!!.modelId)

        repo.updateChatSettings(id)
        assertEquals("Expert", repo.getChat(id)!!.systemPromptName)
        assertEquals("Tu es un expert.", repo.getChat(id)!!.systemPromptText)
    }

    @Test
    fun systemPromptSnapshotIsIndependentFromLibrary() = runBlocking<Unit> {
        val pid = promptRepo.upsert(prompt("Concis", "Réponds court."))
        val sp = promptRepo.get(pid)!!
        val id = newChat(sp)

        promptRepo.upsert(sp.copy(name = "Verbeux", text = "Réponds long."))
        var chat = repo.getChat(id)!!
        assertEquals("Concis", chat.systemPromptName)
        assertEquals("Réponds court.", chat.systemPromptText)

        promptRepo.delete(pid)
        assertNull(promptRepo.get(pid))
        chat = repo.getChat(id)!!
        assertEquals("Concis", chat.systemPromptName)
        assertEquals("Réponds court.", chat.systemPromptText)

        // Un message ajouté n'y touche pas non plus; observeChat voit le même snapshot.
        repo.addUserMessage(id, null, "x")
        assertEquals("Réponds court.", repo.observeChat(id).first()!!.systemPromptText)
    }

    // ------------------------------------------------------------ recherche

    @Test
    fun searchIsCaseAndAccentInsensitiveWithMessageCount() = runBlocking<Unit> {
        val c1 = newChat()
        repo.renameChat(c1, "Recette de crème brûlée")
        val u = repo.addUserMessage(c1, null, "x")
        assistantReply(c1, u.id, "y")
        val c2 = newChat()
        repo.renameChat(c2, "ÉCOLE d'été")
        val c3 = newChat()
        repo.renameChat(c3, "Remise à 100% sûre")
        val c4 = newChat() // sans titre

        fun names(l: List<app.fwchat.domain.ChatSummary>) = l.map { it.id }.toSet()
        assertEquals(setOf(c1, c2, c3, c4), names(repo.observeChatSummaries("").first()))
        assertEquals(setOf(c1), names(repo.observeChatSummaries("creme brulee").first()))
        assertEquals(setOf(c1), names(repo.observeChatSummaries("CRÈME").first()))
        assertEquals(setOf(c2), names(repo.observeChatSummaries("ecole D'ETE").first()))
        assertEquals(setOf(c2), names(repo.observeChatSummaries("école").first()))
        assertEquals(setOf(c1, c3), names(repo.observeChatSummaries("re").first()))
        assertEquals(setOf(c3), names(repo.observeChatSummaries("100%").first()))
        assertTrue(repo.observeChatSummaries("%").first().let { names(it) == setOf(c3) })
        assertTrue(repo.observeChatSummaries("_").first().isEmpty())
        assertTrue(repo.observeChatSummaries("zzz").first().isEmpty())

        val s1 = repo.observeChatSummaries("").first().first { it.id == c1 }
        assertEquals(2, s1.messageCount)
        assertEquals("Recette de crème brûlée", s1.title)
        assertEquals(model, s1.modelId)
        assertEquals(0, repo.observeChatSummaries("").first().first { it.id == c2 }.messageCount)
    }

    @Test
    fun searchFindsAutoTitleAndFollowsRename() = runBlocking<Unit> {
        val id = newChat()
        repo.addUserMessage(id, null, "Où est mon café ?")
        assertEquals(listOf(id), repo.observeChatSummaries("CAFE").first().map { it.id })
        repo.renameChat(id, "Thé")
        assertTrue(repo.observeChatSummaries("cafe").first().isEmpty())
        assertEquals(listOf(id), repo.observeChatSummaries("the").first().map { it.id })
    }

    @Test
    fun summariesAreSortedByUpdatedAtDescAndBumpedByActivity() = runBlocking<Unit> {
        val c1 = newChat()
        val c2 = newChat()
        assertEquals(listOf(c2, c1), repo.observeChatSummaries("").first().map { it.id })
        repo.addUserMessage(c1, null, "hello")
        assertEquals(listOf(c1, c2), repo.observeChatSummaries("").first().map { it.id })
    }

    // ------------------------------------------------------------ sauvegarde

    @Test
    fun exportImportRoundTripReplace() = runBlocking<Unit> {
        val pid = promptRepo.upsert(prompt("Concis", "Réponds court."))
        val id = newChat(promptRepo.get(pid))
        val u1 = repo.addUserMessage(id, null, "Question \"1\"\navec\nlignes — accents é")
        val a1 = repo.addAssistantPlaceholder(id, u1.id, model)
        repo.updateStreaming(a1.id, "Réponse", "pensée")
        repo.finishMessage(a1.id, MessageStatus.COMPLETE, "stop", null, 1, 1, 1)
        val a1b = repo.addAssistantSibling(a1.id, model)
        repo.finishMessage(a1b.id, MessageStatus.COMPLETE, "length", null, 1, 2, 3)
        repo.editInPlace(a1b.id, "modifiée")
        val u1b = repo.editUserAsBranch(u1.id, "Question bis")
        val empty = newChat(null)
        repo.renameChat(empty, "Vide")

        val json = repo.exportAll()
        assertTrue(json.contains("\"fwchat-backup\""))
        assertTrue(json.contains("\"version\":1"))

        // Base fraîche + données parasites qui doivent disparaître avec replace=true.
        val db2 = AppDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        try {
            val repo2 = ChatRepositoryImpl(db2, clock = { 9L }, idGenerator = { "z${++counter}" })
            val prompts2 = SystemPromptRepositoryImpl(db2)
            repo2.createChat("m", null, GenParams())
            prompts2.upsert(prompt("Parasite", "x"))
            repo2.importAll(json, replace = true)

            assertEquals(json, repo2.exportAll().let { fixExportedAt(it, json) })
            assertEquals(setOf(id, empty), repo2.observeChatSummaries("").first().map { it.id }.toSet())
            val chat = repo2.getChat(id)!!
            assertEquals(repo.getChat(id), chat)
            assertEquals(repo.getActivePath(id), repo2.getActivePath(id))
            assertEquals(threadIds(id), repo2.observeThread(id).first().map { it.message.id })
            assertEquals(2, repo2.observeThread(id).first().first().siblingCount)
            assertEquals(u1b.id, chat.selectedRootId)
            assertEquals(repo.getMessage(a1b.id), repo2.getMessage(a1b.id))
            assertEquals("pensée", repo2.getMessage(a1.id)!!.reasoning)
            assertEquals(listOf("Concis"), prompts2.observeAll().first().map { it.name })
            assertEquals(repo.observeChatSummaries("vide").first(), repo2.observeChatSummaries("VIDE").first())
        } finally {
            db2.close()
        }
    }

    /** Le seul champ qui diffère entre deux exports est exportedAt (horloge); on l'aligne pour comparer. */
    private fun fixExportedAt(actual: String, expected: String): String {
        val re = Regex("\"exportedAt\":\\d+")
        return actual.replace(re, re.find(expected)!!.value)
    }

    @Test
    fun importMergeKeepsExistingAndAddsNew() = runBlocking<Unit> {
        val id1 = newChat()
        repo.addUserMessage(id1, null, "un")
        val json = repo.exportAll()

        val id2 = newChat()
        repo.renameChat(id2, "Local")
        repo.renameChat(id1, "Modifié localement")
        repo.importAll(json, replace = false)
        // id1 existe déjà: conservé tel quel (pas de doublon, pas d'écrasement).
        val all = repo.observeChatSummaries("").first()
        assertEquals(setOf(id1, id2), all.map { it.id }.toSet())
        assertEquals("Modifié localement", repo.getChat(id1)!!.title)
        assertEquals(1, all.first { it.id == id1 }.messageCount)

        repo.deleteChat(id1)
        repo.importAll(json, replace = false)
        assertEquals("un", repo.getChat(id1)!!.title)
        assertEquals(setOf(id1, id2), repo.observeChatSummaries("").first().map { it.id }.toSet())
    }

    @Test
    fun invalidImportThrowsAndLeavesDataUntouched() = runBlocking<Unit> {
        val id = newChat()
        repo.renameChat(id, "Garde-moi")
        for (bad in listOf("pas du json", "{}", "[]", """{"format":"autre","version":1}""", """{"format":"fwchat-backup","version":999}""")) {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.importAll(bad, replace = true) } }
        }
        assertEquals("Garde-moi", repo.getChat(id)!!.title)
    }

    @Test
    fun importIgnoresUnknownFieldsAndSanitizesDanglingSelections() = runBlocking<Unit> {
        val json = """
        {"format":"fwchat-backup","version":1,"futur":true,
         "chats":[{"id":"c1","title":"Té","modelId":"m","selectedRootId":"ghost","extra":1,
           "messages":[
             {"id":"m1","role":"USER","content":"a","selectedChildId":"ghost","createdAt":1,"status":"STREAMING"},
             {"id":"m2","parentId":"m1","role":"ASSISTANT","content":"b","createdAt":2,"status":"BIZARRE"},
             {"id":"m3","parentId":"m1","role":"ROBOT","content":"c","createdAt":3}
           ]}]}
        """.trimIndent()
        repo.importAll(json, replace = true)
        val chat = repo.getChat("c1")!!
        assertNull(chat.selectedRootId)
        assertEquals(GenParams(), chat.params)
        assertEquals(listOf("m1", "m2"), repo.getActivePath("c1").map { it.id })
        assertEquals(MessageStatus.INTERRUPTED, repo.getMessage("m1")!!.status)
        assertEquals(MessageStatus.COMPLETE, repo.getMessage("m2")!!.status)
        assertNull(repo.getMessage("m3"))
        assertEquals(listOf("c1"), repo.observeChatSummaries("te").first().map { it.id })
    }

    // ------------------------------------------------------------ prompts système

    @Test
    fun systemPromptRepositoryUpsertGeneratesIdSortsAndKeepsCreatedAt() = runBlocking<Unit> {
        val idB = promptRepo.upsert(prompt("beta", "B"))
        val idA = promptRepo.upsert(prompt("Alpha", "A"))
        assertTrue(idB.isNotBlank())
        assertNotEquals(idA, idB)
        assertEquals(listOf("Alpha", "beta"), promptRepo.observeAll().first().map { it.name })

        val b = promptRepo.get(idB)!!
        assertEquals(5_000L, b.createdAt)
        val later = SystemPromptRepositoryImpl(db, clock = { 9_000L }, idGenerator = idGen)
        assertEquals(idB, later.upsert(b.copy(name = "zeta", text = "B2", family = null)))
        val b2 = later.get(idB)!!
        assertEquals("zeta", b2.name)
        assertEquals("B2", b2.text)
        assertNull(b2.family)
        assertEquals(5_000L, b2.createdAt)
        assertEquals(9_000L, b2.updatedAt)
        assertEquals(listOf("Alpha", "zeta"), promptRepo.observeAll().first().map { it.name })

        promptRepo.delete(idA)
        assertEquals(listOf("zeta"), promptRepo.observeAll().first().map { it.name })
        assertEquals(PromptFamily.GLM, promptRepo.get(promptRepo.upsert(prompt("g", "t")))!!.family)
    }
}
