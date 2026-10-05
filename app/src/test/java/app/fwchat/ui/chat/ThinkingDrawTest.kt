package app.fwchat.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.fwchat.domain.Message
import app.fwchat.domain.MessageStatus
import app.fwchat.domain.Role
import app.fwchat.domain.StreamingText
import app.fwchat.domain.ThreadItem
import app.fwchat.ui.theme.FwChatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Verifie le DESSIN reel (pixels) du bloc de reflexion: un contenu qui deborde de sa boite sans etre rogne se
 * dessine par-dessus les voisins, ce que les tests de bornes de layout ne voient pas.
 * Rendu graphique natif (Robolectric NATIVE) + captureToImage.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ThinkingDrawTest {
    @get:Rule
    val rule = createComposeRule()

    private var mode by mutableStateOf<ThinkingMode?>(null)
    private var reasoning by mutableStateOf("Court")
    private var streaming by mutableStateOf(false)

    private fun msg(role: Role, content: String, reasoning: String?, status: MessageStatus) = Message(
        id = if (role == Role.USER) "u" else "a", chatId = "c", parentId = null, role = role, content = content,
        reasoning = reasoning, selectedChildId = null, createdAt = 1, updatedAt = 1, modelId = null, status = status,
        finishReason = null, error = null, edited = false, promptTokens = null, completionTokens = null,
        reasoningTokens = null,
    )

    private fun lines(n: Int) =
        (1..n).joinToString("\n\n") { i ->
            when (i) {
                1 -> "PREMIERE-LIGNE de la reflexion"
                n -> "DERNIERE-LIGNE de la reflexion"
                else -> "Ligne de reflexion numero $i"
            }
        }

    private fun setContent(streaming: Boolean, initial: ThinkingMode?) {
        this.streaming = streaming
        mode = initial
        rule.setContent {
            FwChatTheme {
                val state = rememberLazyListState()
                val following = remember { mutableStateOf(false) }
                val status = if (this@ThinkingDrawTest.streaming) MessageStatus.STREAMING else MessageStatus.COMPLETE
                val live = this@ThinkingDrawTest.streaming
                val thread = listOf(
                    ThreadItem(msg(Role.USER, "Question de test", null, MessageStatus.COMPLETE), listOf("u")),
                    ThreadItem(
                        msg(Role.ASSISTANT, if (live) "" else "Reponse de test", if (live) null else reasoning, status),
                        listOf("a"),
                    ),
                )
                val ui = ChatUiState(
                    chatId = "c", isDraft = false, loaded = true, thread = thread, generating = live,
                    thinkingOverrides = mode?.let { mapOf("a" to it) } ?: emptyMap(),
                )
                val cb = MessageCallbacks(
                    onCopy = {}, onStartEdit = {}, onCancelEdit = {}, onSaveEdit = { _, _ -> },
                    onSendEdit = { _, _ -> }, onRegenerate = {}, onRetry = {}, onFork = {}, onDelete = {},
                    onSibling = { _, _ -> },
                    onThinkingToggle = { _, m -> mode = ThinkingStates.onHamburger(m) },
                    onThinkingShowAll = { _, m -> mode = ThinkingStates.onShowAll(m) },
                    onThinkingShrink = { _, m -> mode = ThinkingStates.onShrink(m) },
                )
                val streamingState = remember {
                    derivedStateOf {
                        if (this@ThinkingDrawTest.streaming) mapOf("a" to StreamingText("", reasoning)) else emptyMap()
                    }
                }
                Box(Modifier.height(700.dp)) { MessageList(ui, streamingState, cb, state, following) }
            }
        }
        rule.waitForIdle()
    }

    private fun pixels(n: SemanticsNodeInteraction): IntArray {
        val bmp = n.captureToImage().asAndroidBitmap()
        val out = IntArray(bmp.width * bmp.height)
        bmp.getPixels(out, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return out
    }

    private fun rectOf(tag: String): Rect = rect(rule.onNodeWithTag(tag))
    private fun rect(n: SemanticsNodeInteraction): Rect =
        n.getBoundsInRoot().let { Rect(it.left.value, it.top.value, it.right.value, it.bottom.value) }

    private fun count(text: String) = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    private fun assertNoOverlap(upper: Rect, lower: Rect, what: String) {
        assertTrue("$what: chevauchement $upper / $lower", upper.bottom <= lower.top + 0.5f)
    }

    // ---- dessin

    @Test
    fun streamingPreviewDoesNotPaintOverNeighbours() {
        reasoning = "Court"
        setContent(streaming = true, initial = null)
        val bubbleBefore = pixels(rule.onNodeWithText("Question de test"))
        reasoning = lines(200)
        rule.waitForIdle()
        val bubbleAfter = pixels(rule.onNodeWithText("Question de test"))
        assertTrue("la bulle utilisateur a ete recouverte par le texte de reflexion", bubbleBefore.contentEquals(bubbleAfter))
        assertNoOverlap(rect(rule.onNodeWithText("Question de test")), rectOf("thinking-header"), "bulle/entete")
        assertNoOverlap(rectOf("thinking-header"), rectOf("thinking-text"), "entete/texte")
        assertNoOverlap(rectOf("thinking-text"), rectOf("thinking-show-all"), "texte/bouton")
    }

    @Test
    fun finishedPreviewDoesNotPaintOverNeighbours() {
        reasoning = "Court"
        setContent(streaming = false, initial = ThinkingMode.PREVIEW)
        val bubbleBefore = pixels(rule.onNodeWithText("Question de test"))
        val headerBefore = pixels(rule.onNodeWithTag("thinking-header"))
        val answerBefore = pixels(rule.onNodeWithText("Reponse de test"))
        reasoning = lines(200)
        rule.waitForIdle()
        assertTrue("bulle recouverte", bubbleBefore.contentEquals(pixels(rule.onNodeWithText("Question de test"))))
        assertTrue("entete recouvert", headerBefore.contentEquals(pixels(rule.onNodeWithTag("thinking-header"))))
        assertTrue("reponse recouverte", answerBefore.contentEquals(pixels(rule.onNodeWithText("Reponse de test"))))
        assertNoOverlap(rectOf("thinking-text"), rectOf("thinking-show-all"), "texte/bouton")
        assertNoOverlap(rectOf("thinking-show-all"), rect(rule.onNodeWithText("Reponse de test")), "bouton/reponse")
    }

    @Test
    fun noReasoningPixelOutsideThinkingTextBox() {
        // Zone entre le bas du texte de reflexion et le haut du bouton: ne doit contenir que le fond.
        reasoning = lines(200)
        setContent(streaming = false, initial = ThinkingMode.PREVIEW)
        val text = rectOf("thinking-text")
        val button = rectOf("thinking-show-all")
        assertTrue(button.top >= text.bottom - 0.5f)
        val block = rule.onNodeWithTag("thinking-block")
        val blockRect = rect(block)
        val img = block.captureToImage().asAndroidBitmap()
        val scale = img.width / (blockRect.right - blockRect.left)
        val bgFrom = (text.bottom - blockRect.top) * scale
        val bgTo = (button.top - blockRect.top) * scale
        if (bgTo - bgFrom >= 3f) {
            val y0 = bgFrom.toInt() + 1
            val y1 = bgTo.toInt() - 1
            val bg = img.getPixel(1, y0)
            for (y in y0..y1) for (x in 0 until img.width) {
                assertEquals("pixel de texte hors boite en ($x,$y)", bg, img.getPixel(x, y))
            }
        }
    }

    // ---- comportement

    @Test
    fun previewShowsFirstLinesAndIsFixedWhileStreaming() {
        reasoning = lines(10)
        setContent(streaming = true, initial = null)
        var firstHeight = -1f
        var firstPixels: IntArray? = null
        for (n in listOf(10, 40, 200, 500)) {
            reasoning = lines(n)
            rule.waitForIdle()
            assertTrue("1re ligne absente pour n=$n", count("PREMIERE-LIGNE") >= 1)
            assertEquals("derniere ligne visible pour n=$n", 0, count("DERNIERE-LIGNE"))
            val r = rectOf("thinking-text")
            val h = r.bottom - r.top
            assertTrue("apercu > 5 lignes: $h", h <= 5 * 24f + 8f)
            val px = pixels(rule.onNodeWithTag("thinking-text"))
            val ref = firstPixels
            if (ref == null) {
                firstHeight = h
                firstPixels = px
            } else {
                assertEquals("hauteur stable", firstHeight, h, 0.5f)
                assertTrue("le dessin de l'apercu a change pendant le streaming (n=$n)", ref.contentEquals(px))
            }
        }
    }

    @Test
    fun streamingAndFinishedPreviewHaveSameHeight() {
        reasoning = lines(200)
        setContent(streaming = true, initial = null)
        val live = rectOf("thinking-text").let { it.bottom - it.top }
        streaming = false
        mode = ThinkingMode.PREVIEW
        rule.waitForIdle()
        val done = rectOf("thinking-text").let { it.bottom - it.top }
        assertTrue(live > 0f)
        assertEquals("hauteur apercu live vs final", live, done, 0.5f)
    }

    @Test
    fun showAllGrowsDownwardWithoutOverlap() {
        reasoning = lines(200)
        setContent(streaming = false, initial = ThinkingMode.PREVIEW)
        val topBefore = rectOf("thinking-header").top
        val bubble = rect(rule.onNodeWithText("Question de test"))
        rule.onNodeWithTag("thinking-show-all").performClick()
        rule.waitForIdle()
        assertEquals("le haut ne doit pas bouger", topBefore, rectOf("thinking-header").top, 0.5f)
        assertNoOverlap(bubble, rectOf("thinking-header"), "bulle/entete")
        assertEquals(bubble, rect(rule.onNodeWithText("Question de test")))
    }
}
