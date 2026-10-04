package app.fwchat.ui.chat

import app.fwchat.domain.GenParams
import app.fwchat.domain.Message
import app.fwchat.domain.MessageStatus
import app.fwchat.domain.Role
import app.fwchat.domain.StreamingText
import app.fwchat.ui.message
import app.fwchat.ui.systemPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ChatLogicTest {

    // ------------------------------------------------------------------ thinking

    @Test
    fun thinkingDefautApercuEnStreamingEtRepliePourLHistorique() {
        assertEquals(ThinkingMode.PREVIEW, ThinkingStates.default(messageStreaming = true))
        assertEquals(ThinkingMode.COLLAPSED, ThinkingStates.default(messageStreaming = false))
        assertEquals(ThinkingMode.PREVIEW, ThinkingStates.resolve(null, true))
        assertEquals(ThinkingMode.COLLAPSED, ThinkingStates.resolve(null, false))
    }

    @Test
    fun thinkingChoixExplicitPrimeSurLeDefautMemeALaFinDuMessage() {
        assertEquals(ThinkingMode.FULL, ThinkingStates.resolve(ThinkingMode.FULL, messageStreaming = false))
        assertEquals(ThinkingMode.COLLAPSED, ThinkingStates.resolve(ThinkingMode.COLLAPSED, messageStreaming = true))
    }

    @Test
    fun thinkingHamburgerReplieSiOuvertEtRouvreEnApercuSiReplie() {
        assertEquals(ThinkingMode.COLLAPSED, ThinkingStates.onHamburger(ThinkingMode.PREVIEW))
        assertEquals(ThinkingMode.COLLAPSED, ThinkingStates.onHamburger(ThinkingMode.FULL))
        assertEquals(ThinkingMode.PREVIEW, ThinkingStates.onHamburger(ThinkingMode.COLLAPSED))
    }

    @Test
    fun thinkingToutAfficherPuisReduire() {
        assertEquals(ThinkingMode.FULL, ThinkingStates.onShowAll(ThinkingMode.PREVIEW))
        assertEquals(ThinkingMode.COLLAPSED, ThinkingStates.onShowAll(ThinkingMode.COLLAPSED))
        assertEquals(ThinkingMode.PREVIEW, ThinkingStates.onShrink(ThinkingMode.FULL))
        assertEquals(ThinkingMode.PREVIEW, ThinkingStates.onShrink(ThinkingMode.PREVIEW))
    }

    @Test
    fun thinkingCycleComplet() {
        var m = ThinkingStates.default(true)          // apercu
        m = ThinkingStates.onShowAll(m)               // complet
        m = ThinkingStates.onHamburger(m)             // replie
        m = ThinkingStates.onHamburger(m)             // apercu
        assertEquals(ThinkingMode.PREVIEW, m)
    }

    @Test
    fun thinkingIndicateurTantQueLaReponseNAPasCommence() {
        assertTrue(ThinkingStates.isThinking(MessageStatus.STREAMING, ""))
        assertFalse(ThinkingStates.isThinking(MessageStatus.STREAMING, "Bonjour"))
        assertFalse(ThinkingStates.isThinking(MessageStatus.COMPLETE, ""))
    }

    @Test
    fun thinkingDebordementDeLApercu() {
        assertFalse(ThinkingStates.mayOverflowPreview("court"))
        assertFalse(ThinkingStates.mayOverflowPreview("a\nb\nc\nd\ne"))
        assertTrue(ThinkingStates.mayOverflowPreview("a\nb\nc\nd\ne\nf"))
        assertTrue(ThinkingStates.mayOverflowPreview("x".repeat(300)))
    }

    // ------------------------------------------------------------------ brouillon

    @Test
    fun snapshotDuPromptResoutLesBalisesSansToucherAuPromptDeLaBibliotheque() {
        val now = ZonedDateTime.of(2026, 1, 2, 9, 5, 0, 0, ZoneId.of("Europe/Paris"))
        val lib = systemPrompt("p", "Nom", "Date: {{date_iso}} a {{heure}}")
        val draft = ChatDraft("m", lib, GenParams())
        val snap = draft.promptSnapshot(now)!!
        assertEquals("Date: 2026-01-02 a 09:05", snap.text)
        assertEquals("p", snap.id)
        assertEquals("Date: {{date_iso}} a {{heure}}", lib.text)
        assertNull(ChatDraft("m", null, GenParams()).promptSnapshot(now))
    }

    // ------------------------------------------------------------------ copie de la conversation

    private fun msg(id: String, role: Role, content: String, status: MessageStatus = MessageStatus.COMPLETE): Message =
        message(id).copy(role = role, content = content, status = status)

    @Test
    fun formatDeLaConversationCopiee() {
        val text = ConversationFormatter.format(
            title = "",
            modelId = "accounts/fireworks/models/glm-5p3",
            promptName = "Mon prompt",
            messages = listOf(
                msg("1", Role.USER, "Salut"),
                msg("2", Role.ASSISTANT, "Bonjour  \n").copy(modelId = "accounts/fireworks/models/glm-5p3"),
            ),
        )
        val expected = "Nouveau chat\n" +
            "Modèle : glm-5p3\n" +
            "Prompt système : Mon prompt\n" +
            "\n" +
            "Vous :\nSalut\n" +
            "\n" +
            "Assistant (glm-5p3) :\nBonjour\n"
        assertEquals(expected, text)
    }

    @Test
    fun formatSansPromptNiModeleEtStatuts() {
        val text = ConversationFormatter.format(
            title = "Titre",
            modelId = null,
            promptName = null,
            messages = listOf(
                msg("1", Role.ASSISTANT, "Partiel", MessageStatus.INTERRUPTED),
                msg("2", Role.ASSISTANT, "", MessageStatus.ERROR).copy(error = "Boom"),
            ),
        )
        assertTrue(text.startsWith("Titre\n\nAssistant :\nPartiel\n[Interrompu]\n"))
        assertTrue(text.contains("[Erreur : Boom]"))
        assertFalse(text.contains("Prompt syst"))
    }

    @Test
    fun formatUtiliseLeTexteLiveDesMessagesEnStreaming() {
        val text = ConversationFormatter.format(
            title = "T", modelId = null, promptName = null,
            messages = listOf(msg("a", Role.ASSISTANT, "", MessageStatus.STREAMING)),
            live = mapOf("a" to StreamingText("Texte en cours", "")),
        )
        assertTrue(text.contains("Texte en cours"))
    }

    @Test
    fun metaSousLaReponse() {
        val m = message("1").copy(modelId = "accounts/fireworks/models/glm-5p3", completionTokens = 1240)
        val meta = ConversationFormatter.meta(m)!!
        assertTrue(meta.startsWith("glm-5p3 · 1"))
        assertTrue(meta.endsWith("240 tokens"))
        assertNull(ConversationFormatter.meta(message("2")))
        assertTrue(ConversationFormatter.meta(m.copy(edited = true))!!.endsWith("modifié"))
    }
}
