package app.fwchat.ui.chat

import androidx.compose.runtime.saveable.SaverScope
import app.fwchat.domain.EngineEvent
import app.fwchat.domain.Message
import app.fwchat.domain.MessageStatus
import app.fwchat.domain.Role
import app.fwchat.domain.ThreadItem
import app.fwchat.ui.message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSupportTest {

    private fun msg(
        id: String,
        status: MessageStatus = MessageStatus.COMPLETE,
        content: String = "x",
        reasoning: String? = null,
        updatedAt: Long = 0,
        role: Role = Role.ASSISTANT,
        parent: String? = null,
    ): Message = message(id).copy(
        status = status, content = content, reasoning = reasoning, updatedAt = updatedAt, role = role, parentId = parent,
    )

    private fun item(m: Message, vararg siblings: String) = ThreadItem(m, if (siblings.isEmpty()) listOf(m.id) else siblings.toList())

    // ------------------------------------------------------------------ ThreadDiff

    @Test
    fun threadDiffIgnoreLeContenuDUnMessageEnStreaming() {
        val a = item(msg("a", MessageStatus.STREAMING, content = "deb", updatedAt = 1))
        val b = item(msg("a", MessageStatus.STREAMING, content = "debut plus long", reasoning = "r", updatedAt = 2))
        assertTrue(ThreadDiff.sameForUi(a, b))
    }

    @Test
    fun threadDiffVoitUnChangementDeStatutOuDeContenuHorsStreaming() {
        val streaming = item(msg("a", MessageStatus.STREAMING, content = "deb"))
        val complete = item(msg("a", MessageStatus.COMPLETE, content = "debut"))
        assertFalse(ThreadDiff.sameForUi(streaming, complete))

        val c1 = item(msg("b", content = "un"))
        val c2 = item(msg("b", content = "deux"))
        assertFalse(ThreadDiff.sameForUi(c1, c2))
    }

    @Test
    fun threadDiffVoitUnChangementDeFreresOuDeChampsHorsTexte() {
        val a = item(msg("a", MessageStatus.STREAMING), "a")
        val b = item(msg("a", MessageStatus.STREAMING), "z", "a")
        assertFalse(ThreadDiff.sameForUi(a, b))
        val c = item(msg("a", MessageStatus.STREAMING).copy(error = "boom"))
        assertFalse(ThreadDiff.sameForUi(a, c))
    }

    @Test
    fun stabilizeRenvoieLaMemeListeQuandSeulLeTexteLiveBouge() {
        val user = item(msg("u", role = Role.USER, content = "Question"))
        val live1 = item(msg("a", MessageStatus.STREAMING, content = "", parent = "u"))
        val previous = listOf(user, live1)
        val next = listOf(
            item(msg("u", role = Role.USER, content = "Question")),
            item(msg("a", MessageStatus.STREAMING, content = "checkpoint", updatedAt = 99, parent = "u")),
        )
        val out = ThreadDiff.stabilize(previous, next)
        assertSame(previous, out)
    }

    @Test
    fun stabilizeReutiliseLesInstancesInchangeesEtRemplaceLesAutres() {
        val u = item(msg("u", role = Role.USER, content = "Q"))
        val a = item(msg("a", MessageStatus.STREAMING, parent = "u"))
        val previous = listOf(u, a)
        val done = item(msg("a", MessageStatus.COMPLETE, content = "Fini", parent = "u"))
        val out = ThreadDiff.stabilize(previous, listOf(item(msg("u", role = Role.USER, content = "Q")), done))
        assertNotSame(previous, out)
        assertSame(u, out[0])
        assertSame(done, out[1])
    }

    @Test
    fun stabilizeGereAjoutEtSuppression() {
        val u = item(msg("u", role = Role.USER))
        val a = item(msg("a", parent = "u"))
        val out1 = ThreadDiff.stabilize(listOf(u, a), listOf(u))
        assertEquals(listOf(u), out1)
        val out2 = ThreadDiff.stabilize(listOf(u), listOf(u, a))
        assertEquals(2, out2.size)
        assertSame(u, out2[0])
        assertEquals(emptyList<ThreadItem>(), ThreadDiff.stabilize(listOf(u), emptyList()))
    }

    // ------------------------------------------------------------------ Saver

    private fun save(text: String): Any? = with(BoundedTextSaver) { SaverScope { true }.save(text) }

    @Test
    fun saverNeSauvegardePasAuDelaDeLaLimite() {
        assertEquals("court", save("court"))
        assertEquals("x".repeat(SAVED_TEXT_MAX_CHARS), save("x".repeat(SAVED_TEXT_MAX_CHARS)))
        assertNull(save("x".repeat(SAVED_TEXT_MAX_CHARS + 1)))
        assertNull(save("y".repeat(400_000)))
    }

    @Test
    fun saverRestaureLeTexteSauvegarde() {
        val restored = with(BoundedTextSaver) { restore("abc") }
        assertEquals("abc", restored)
    }

    // ------------------------------------------------------------------ LongText

    @Test
    fun seuilDeMessageLong() {
        assertFalse(LongText.isLong("a".repeat(LongText.THRESHOLD_CHARS)))
        assertTrue(LongText.isLong("a".repeat(LongText.THRESHOLD_CHARS + 1)))
    }

    @Test
    fun apercuLimiteLesCaracteres() {
        val text = ("mot ".repeat(2000))
        val p = LongText.preview(text)
        assertTrue(p.length <= LongText.PREVIEW_CHARS)
        assertTrue(p.length > LongText.PREVIEW_CHARS - 250)
        assertTrue("coupe sur un mot entier", text.startsWith(p))
        assertTrue(p.endsWith("mot"))
    }

    @Test
    fun apercuLimiteLesLignes() {
        val text = (1..100).joinToString("\n") { "ligne $it" }
        val p = LongText.preview(text)
        assertEquals(LongText.PREVIEW_LINES, p.lines().size)
        assertEquals("ligne 12", p.lines().last())
    }

    @Test
    fun apercuDUnTexteCourtEstLeTexteEntier() {
        assertEquals("bonjour\nmonde", LongText.preview("bonjour\nmonde"))
    }

    @Test
    fun apercuSansEspaceCoupeALaDure() {
        val p = LongText.preview("a".repeat(10_000))
        assertEquals(LongText.PREVIEW_CHARS, p.length)
    }

    @Test
    fun tranchesRecomposentExactementLeTexte() {
        val text = buildString {
            repeat(400) { append("Paragraphe numero $it avec un peu de texte pour remplir.\n\n") }
            append("x".repeat(9000)) // un mot geant sans espace
            append("\nfin")
        }
        val chunks = LongText.chunks(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.isNotEmpty() && it.length <= LongText.CHUNK_CHARS })
        assertTrue(chunks.size > 10)
    }

    @Test
    fun tranchesPreferentLesFinsDeParagraphe() {
        val para = "a".repeat(1000)
        val text = listOf(para, para, para, para, para).joinToString("\n\n")
        val chunks = LongText.chunks(text, maxChars = 2500)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.dropLast(1).all { it.endsWith("\n\n") })
    }

    @Test
    fun tranchesDUnTexteCourtEtTexteVide() {
        assertEquals(listOf("abc"), LongText.chunks("abc"))
        assertEquals(listOf(""), LongText.chunks(""))
    }

    // ------------------------------------------------------------------ erreurs du moteur par chat

    @Test
    fun erreurDUnAutreChatEstIgnoree() {
        val e = EngineEvent.Error("boom", chatId = "autre")
        assertNull(e.noticeFor("visible"))
        assertNull(e.noticeFor(null))
    }

    @Test
    fun erreurDuChatVisibleEtErreurGlobaleSontAffichees() {
        assertEquals(EngineNotice.Message("boom"), EngineEvent.Error("boom", chatId = "visible").noticeFor("visible"))
        assertEquals(EngineNotice.Message("g"), EngineEvent.Error("g").noticeFor("visible"))
        assertEquals(EngineNotice.Message("g"), EngineEvent.Error("g").noticeFor(null))
    }

    @Test
    fun unauthorizedResteGlobal() {
        assertEquals(EngineNotice.Unauthorized, EngineEvent.Unauthorized.noticeFor("visible"))
        assertEquals(EngineNotice.Unauthorized, EngineEvent.Unauthorized.noticeFor(null))
    }

    // ------------------------------------------------------------------ brouillon et reessayer

    @Test
    fun leChampNEstVideImmediatementQuePourUnChatExistant() {
        assertTrue(clearsDraftImmediately(isDraftChat = false, accepted = true))
        assertFalse(clearsDraftImmediately(isDraftChat = true, accepted = true))
        assertFalse(clearsDraftImmediately(isDraftChat = false, accepted = false))
    }

    @Test
    fun laPermissionNotificationsNEstDemandeeQuUneFois() {
        assertTrue(shouldAskNotificationPermission(granted = false, alreadyAsked = false))
        assertFalse(shouldAskNotificationPermission(granted = false, alreadyAsked = true))
        assertFalse(shouldAskNotificationPermission(granted = true, alreadyAsked = false))
    }

    @Test
    fun retryCleanupDetecteLeRemplacantTermine() {
        val failed = msg("e", MessageStatus.ERROR, content = "", parent = "u")
        assertTrue(RetryCleanup.isDroppable(failed))
        assertFalse(RetryCleanup.isDroppable(failed.copy(content = "du texte utile")))
        assertFalse(RetryCleanup.isDroppable(failed.copy(status = MessageStatus.COMPLETE)))

        // pas encore de frere
        assertNull(RetryCleanup.replacement(listOf(item(failed)), "e"))
        // frere en cours
        val streaming = msg("n", MessageStatus.STREAMING, parent = "u")
        assertNull(RetryCleanup.replacement(listOf(item(streaming, "e", "n")), "e"))
        // frere termine
        val done = streaming.copy(status = MessageStatus.COMPLETE)
        val found = RetryCleanup.replacement(listOf(item(done, "e", "n")), "e")
        assertNotNull(found)
        assertTrue(RetryCleanup.shouldDropFailed(found!!))
        assertTrue(RetryCleanup.shouldDropFailed(done.copy(status = MessageStatus.ERROR)))
        assertFalse(RetryCleanup.shouldDropFailed(done.copy(status = MessageStatus.INTERRUPTED)))
    }
}
