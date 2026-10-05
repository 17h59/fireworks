package app.fwchat.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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

/** Mise en page reelle du bloc de reflexion dans la LazyColumn de MessageList (Robolectric + Compose). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ThinkingLayoutTest {
    @get:Rule
    val rule = createComposeRule()

    private var mode by mutableStateOf<ThinkingMode?>(null)
    private var live by mutableStateOf(mapOf<String, StreamingText>())
    private val finalReasoning = lines(200)

    private fun msg(role: Role, content: String, reasoning: String?, status: MessageStatus) = Message(
        id = if (role == Role.USER) "u" else "a", chatId = "c", parentId = null, role = role, content = content,
        reasoning = reasoning, selectedChildId = null, createdAt = 1, updatedAt = 1, modelId = null, status = status,
        finishReason = null, error = null, edited = false, promptTokens = null, completionTokens = null,
        reasoningTokens = null,
    )

    private fun lines(n: Int) = (1..n).joinToString("\n\n") { "Ligne de reflexion numero $it" }

    private fun top(tag: String) = rule.onNodeWithTag(tag).getBoundsInRoot().top.value
    private fun bottom(tag: String) = rule.onNodeWithTag(tag).getBoundsInRoot().bottom.value
    private fun hOf(tag: String) = rule.onNodeWithTag(tag).getBoundsInRoot().let { it.bottom.value - it.top.value }

    private fun setContent(streaming: Boolean, initial: ThinkingMode?) {
        mode = initial
        rule.setContent {
            FwChatTheme {
                val state = rememberLazyListState()
                val following = remember { mutableStateOf(true) }
                val status = if (streaming) MessageStatus.STREAMING else MessageStatus.COMPLETE
                val thread = listOf(
                    ThreadItem(msg(Role.USER, "Question", null, MessageStatus.COMPLETE), listOf("u")),
                    ThreadItem(
                        msg(Role.ASSISTANT, if (streaming) "" else "Reponse", if (streaming) null else finalReasoning, status),
                        listOf("a"),
                    ),
                )
                val ui = ChatUiState(
                    chatId = "c", isDraft = false, loaded = true, thread = thread, generating = streaming,
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
                val streamingState = remember { derivedStateOf { live } }
                LaunchedEffect(Unit) { state.scrollToBottom() }
                Box(Modifier.height(600.dp)) { MessageList(ui, streamingState, cb, state, following) }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun previewHeightIsBoundedAndStableWhileStreaming() {
        live = mapOf("a" to StreamingText("", lines(3)))
        setContent(streaming = true, initial = null)
        var last = -1f
        for (n in listOf(3, 10, 40, 200)) {
            live = mapOf("a" to StreamingText("", lines(n)))
            rule.waitForIdle()
            val h = hOf("thinking-text")
            // 5 lignes de 16-20 dp + marges: borne tolerante.
            assertTrue("hauteur apercu $h", h <= 5 * 24f)
            if (n >= 10 && last >= 0f) assertEquals("hauteur stable", last, h, 1f)
            if (n >= 10) last = h
        }
    }

    @Test
    fun previewOfFinishedMessageIsBounded() {
        setContent(streaming = false, initial = ThinkingMode.PREVIEW)
        assertTrue(hOf("thinking-text") <= 5 * 24f)
    }

    @Test
    fun showAllGrowsDownwardWithoutMovingTopFinished() {
        setContent(streaming = false, initial = ThinkingMode.PREVIEW)
        val topBefore = top("thinking-header")
        val bottomBefore = bottom("thinking-show-all")
        assertTrue(bottomBefore > top("thinking-header"))
        rule.onNodeWithTag("thinking-show-all").performClick()
        rule.waitForIdle()
        assertEquals("le haut ne doit pas bouger", topBefore, top("thinking-header"), 1f)
        // Le texte complet est rendu en flux sous l'en-tete (le bouton Reduire est tout en bas, hors ecran).
        rule.onNodeWithTag("thinking-show-all").assertDoesNotExist()
    }

    @Test
    fun showAllGrowsDownwardWithoutMovingTopStreaming() {
        live = mapOf("a" to StreamingText("", lines(200)))
        setContent(streaming = true, initial = null)
        val topBefore = top("thinking-header")
        rule.onNodeWithTag("thinking-show-all").performClick()
        rule.waitForIdle()
        assertEquals("le haut ne doit pas bouger", topBefore, top("thinking-header"), 1f)
        assertTrue(hOf("thinking-text") > 5 * 24f)
    }

    @Test
    fun collapsedShowsHeaderOnly() {
        setContent(streaming = false, initial = ThinkingMode.COLLAPSED)
        rule.onNodeWithTag("thinking-header").assertExists()
        rule.onNodeWithTag("thinking-text").assertDoesNotExist()
    }
}
