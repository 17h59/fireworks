package app.fwchat.ui.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fwchat.domain.Message
import app.fwchat.domain.MessageStatus
import app.fwchat.domain.Role
import app.fwchat.domain.StreamingText
import app.fwchat.domain.ThreadItem
import app.fwchat.ui.markdown.MarkdownBlocksCache
import app.fwchat.ui.markdown.MarkdownStyle
import app.fwchat.ui.markdown.markdownItems
import app.fwchat.ui.markdown.rememberMarkdownBlocksCache
import app.fwchat.ui.markdown.rememberMarkdownStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Actions déclenchées depuis les messages (créées une seule fois par écran: instance stable). */
@Stable
class MessageCallbacks(
    val onCopy: (String) -> Unit,
    val onStartEdit: (String) -> Unit,
    val onCancelEdit: () -> Unit,
    val onSaveEdit: (messageId: String, text: String) -> Unit,
    val onSendEdit: (messageId: String, text: String) -> Unit,
    val onRegenerate: (String) -> Unit,
    val onFork: (String) -> Unit,
    val onDelete: (String) -> Unit,
    val onSibling: (ThreadItem, Int) -> Unit,
    val onThinkingToggle: (String, ThinkingMode) -> Unit,
    val onThinkingShowAll: (String, ThinkingMode) -> Unit,
    val onThinkingShrink: (String, ThinkingMode) -> Unit,
)

private data class FollowSignal(
    val total: Int,
    val lastIndex: Int,
    val lastEnd: Int,
    val viewport: Int,
    val canScrollForward: Boolean,
)

/**
 * Descend tout en bas, même si le dernier élément est plus haut que l'écran.
 * Tolère l'interruption par un geste de l'utilisateur (la coroutine appelante n'est pas annulée pour autant).
 */
internal suspend fun LazyListState.scrollToBottom() {
    try {
        val total = layoutInfo.totalItemsCount
        if (total == 0) return
        if (layoutInfo.visibleItemsInfo.lastOrNull()?.index != total - 1) scrollToItem(total - 1)
        var guard = 0
        while (canScrollForward && guard++ < 3) scrollBy(SCROLL_TO_END_PX)
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
    }
}

private const val SCROLL_TO_END_PX = 1_000_000f

/**
 * Liste des messages du chemin actif.
 *
 * Performance: la lambda de la LazyColumn ne lit JAMAIS [streaming] (seuls les items du message en cours le lisent,
 * à l'intérieur de leur propre composition): un token ne recompose donc que ce message.
 *
 * Suivi du bas ([following]): tant qu'il est vrai, la vue colle au bas même quand le message en cours grandit.
 * Un geste de défilement vers le haut le désactive; il se réactive quand l'utilisateur revient en bas.
 */
@Composable
internal fun MessageList(
    ui: ChatUiState,
    streaming: State<Map<String, StreamingText>>,
    callbacks: MessageCallbacks,
    listState: LazyListState,
    following: MutableState<Boolean>,
    modifier: Modifier = Modifier,
) {
    val style = rememberMarkdownStyle()
    val thinkStyle = rememberThinkingStyle(style)
    val cache = rememberMarkdownBlocksCache()

    val connection = remember(following, listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Doigt qui descend = le contenu remonte vers le début: l'utilisateur lit plus haut.
                if (source == NestedScrollSource.UserInput && available.y > 0f && listState.canScrollBackward) {
                    following.value = false
                }
                return Offset.Zero
            }
        }
    }

    // Suivi du bas: réagit à toute variation de la mise en page (texte qui grandit, clavier, nouveaux messages).
    LaunchedEffect(listState, following) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            FollowSignal(
                total = info.totalItemsCount,
                lastIndex = last?.index ?: -1,
                lastEnd = (last?.offset ?: 0) + (last?.size ?: 0),
                viewport = info.viewportEndOffset - info.viewportStartOffset,
                canScrollForward = listState.canScrollForward,
            )
        }.collect { s ->
            if (following.value && s.total > 0 && s.canScrollForward) listState.scrollToBottom()
        }
    }

    // Retour en bas par le geste: le suivi reprend.
    LaunchedEffect(listState, following) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && !listState.canScrollForward) following.value = true
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.nestedScroll(connection),
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
    ) {
        for (ti in ui.thread) {
            val m = ti.message
            when (m.role) {
                Role.USER -> userItems(ti, ui, callbacks, style)
                Role.ASSISTANT -> assistantItems(ti, ui, streaming, callbacks, style, thinkStyle, cache)
            }
        }
    }
}

private fun LazyListScope.userItems(
    ti: ThreadItem,
    ui: ChatUiState,
    cb: MessageCallbacks,
    style: MarkdownStyle,
) {
    val m = ti.message
    if (ui.editingMessageId == m.id) {
        item(key = "m:${m.id}:edit", contentType = "edit") {
            EditBox(message = m, isUser = true, generating = ui.generating, cb = cb)
        }
        return
    }
    item(key = "m:${m.id}:user", contentType = "user") {
        UserBubble(m.content, style)
    }
    item(key = "m:${m.id}:actions", contentType = "actions") {
        ActionsBar(ti, assistant = false, generating = ui.generating, cb = cb)
    }
}

private fun LazyListScope.assistantItems(
    ti: ThreadItem,
    ui: ChatUiState,
    streaming: State<Map<String, StreamingText>>,
    cb: MessageCallbacks,
    style: MarkdownStyle,
    thinkStyle: MarkdownStyle,
    cache: MarkdownBlocksCache,
) {
    val m = ti.message
    val live = m.status == MessageStatus.STREAMING
    val editing = ui.editingMessageId == m.id
    val mode = ThinkingStates.resolve(ui.thinkingOverrides[m.id], messageStreaming = live)

    // 1. Bloc de réflexion
    if (live) {
        item(key = "m:${m.id}:think", contentType = "think") {
            LiveThinking(m, streaming, mode, cb, thinkStyle)
        }
    } else if (!m.reasoning.isNullOrBlank()) {
        thinkingItems(m, m.reasoning, mode, cb, thinkStyle, cache)
    }

    // 2. Corps de la réponse
    if (editing) {
        item(key = "m:${m.id}:edit", contentType = "edit") {
            EditBox(message = m, isUser = false, generating = ui.generating, cb = cb)
        }
        return
    }
    if (live) {
        item(key = "m:${m.id}:live", contentType = "live") {
            LiveBody(m, streaming, style)
        }
        return
    }
    if (m.content.isNotEmpty()) {
        markdownItems(
            key = "m:${m.id}:b",
            blocks = cache.blocks(m.id, m.content),
            style = style,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }

    // 3. Pied: erreur / interruption / méta + actions
    item(key = "m:${m.id}:foot", contentType = "foot") {
        AssistantFooter(ti, ui.generating, cb)
    }
}

@Composable
private fun rememberThinkingStyle(base: MarkdownStyle): MarkdownStyle {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    return remember(base, color) {
        fun TextStyle.dim() = copy(
            fontSize = (fontSize.value - 2f).coerceAtLeast(12f).sp,
            lineHeight = (lineHeight.value - 3f).coerceAtLeast(16f).sp,
            color = color,
        )
        base.copy(
            body = base.body.dim(),
            headings = base.headings.map { it.dim() },
            table = base.table.dim(),
            code = base.code.copy(color = color),
        )
    }
}

internal fun Message.isLive(): Boolean = status == MessageStatus.STREAMING

@Composable
internal fun rememberLiveText(
    id: String,
    streaming: State<Map<String, StreamingText>>,
): State<StreamingText?> = remember(id, streaming) { derivedStateOf { streaming.value[id] } }
