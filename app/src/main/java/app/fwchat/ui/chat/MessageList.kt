package app.fwchat.ui.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fwchat.domain.Message
import app.fwchat.domain.MessageStatus
import app.fwchat.domain.Role
import app.fwchat.domain.StreamingText
import app.fwchat.domain.ThreadItem
import app.fwchat.ui.markdown.MarkdownBlocksCache
import app.fwchat.ui.markdown.MarkdownBlocksLoader
import app.fwchat.ui.markdown.MarkdownStyle
import app.fwchat.ui.markdown.MarkdownText
import app.fwchat.ui.markdown.StreamStable
import app.fwchat.ui.markdown.StreamingMarkdown
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
    /** « Réessayer » sur une erreur (supprime l'erreur vide précédente une fois la nouvelle génération terminée). */
    val onRetry: (String) -> Unit,
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

/** Réponse terminée de cette taille ou moins: UN item (Column de blocs), parsé à la composition (visible seulement). */
internal const val SMALL_MESSAGE_CHARS = 4_000

/** Texte brut affiché à la place d'un gros message le temps de son parse hors thread principal. */
private const val PLACEHOLDER_CHARS = 1_200

/** Parseur + partie stable d'un message en cours de génération (voir [LiveMarkdowns]). */
@Stable
internal class LiveEntry(
    val id: String,
    /** Texte à utiliser si le moteur n'a pas (encore) de texte live pour ce message. */
    val fallback: String,
    val markdown: StreamingMarkdown,
    val stable: State<StreamStable>,
)

/**
 * Un [StreamingMarkdown] par message en cours de génération. Le parseur incrémental vient du cache LRU
 * (`cache.incremental(id)`): à la fin du flux, le rendu final le reprend et ne re-parse que la fin du message.
 */
@Stable
internal class LiveMarkdowns(
    private val cache: MarkdownBlocksCache,
    private val streaming: State<Map<String, StreamingText>>,
) {
    private val entries = HashMap<String, LiveEntry>()

    fun entry(m: Message): LiveEntry = entries.getOrPut(m.id) {
        val id = m.id
        val fallback = m.content
        val md = StreamingMarkdown(cache.incremental(id, streaming = true))
        LiveEntry(id, fallback, md, md.stableState { streaming.value[id]?.content ?: fallback })
    }

    /** Oublie les messages qui ne sont plus en cours de génération. */
    fun retain(liveIds: Set<String>) {
        if (entries.keys.any { it !in liveIds }) entries.keys.retainAll(liveIds)
    }
}

/**
 * Liste des messages du chemin actif.
 *
 * Performance (très gros textes):
 * - le lambda de la LazyColumn ne lit JAMAIS [streaming] directement: seuls les items du message en cours le lisent;
 * - un message en cours émet en items `markdownItems` ses seuls blocs FERMÉS ([LiveMarkdowns]; l'état lu ne change
 *   que quand un bloc se ferme), le dernier bloc ouvert est rendu dans UN item « vivant » ([LiveBody]) — un token
 *   ne recompose donc que cet item, jamais les centaines de blocs déjà finis;
 * - les gros messages terminés sont parsés hors thread principal ([MarkdownBlocksLoader], texte brut en attendant),
 *   les petits à la composition de leur item (donc seulement s'ils sont visibles);
 * - un message utilisateur très long est replié ([LongText]).
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
    contentPadding: PaddingValues = PaddingValues(),
) {
    val style = rememberMarkdownStyle()
    val thinkStyle = rememberThinkingStyle(style)
    val cache = rememberMarkdownBlocksCache()
    val scope = rememberCoroutineScope()
    val loader = remember(cache, scope) { MarkdownBlocksLoader(cache, scope) }
    val live = remember(cache, streaming) { LiveMarkdowns(cache, streaming) }
    // Messages utilisateur longs dépliés (état d'affichage local, lu par le lambda de la LazyColumn).
    val expanded = remember { mutableStateListOf<String>() }

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
        contentPadding = contentPadding,
    ) {
        // Parse des gros messages terminés: demandé du plus récent au plus ancien (le bas de l'écran d'abord).
        for (k in ui.thread.indices.reversed()) {
            val m = ui.thread[k].message
            if (m.role == Role.ASSISTANT && !m.isLive() && m.content.length > SMALL_MESSAGE_CHARS && ui.editingMessageId != m.id) {
                loader.blocks(m.id, m.content)
            }
        }
        val liveIds = HashSet<String>()
        for (ti in ui.thread) {
            val m = ti.message
            when (m.role) {
                Role.USER -> userItems(ti, ui, callbacks, style, expanded)
                Role.ASSISTANT -> {
                    if (m.isLive() && ui.editingMessageId != m.id) liveIds.add(m.id)
                    assistantItems(ti, ui, streaming, callbacks, style, thinkStyle, loader, live)
                }
            }
        }
        live.retain(liveIds)
    }
}

private fun LazyListScope.userItems(
    ti: ThreadItem,
    ui: ChatUiState,
    cb: MessageCallbacks,
    style: MarkdownStyle,
    expanded: SnapshotStateList<String>,
) {
    val m = ti.message
    if (ui.editingMessageId == m.id) {
        item(key = "m:${m.id}:edit", contentType = "edit") {
            EditBox(message = m, isUser = true, generating = ui.generating, cb = cb)
        }
        return
    }
    if (!LongText.isLong(m.content)) {
        item(key = "m:${m.id}:user", contentType = "user") {
            UserBubble(m.content, style)
        }
    } else if (m.id !in expanded) {
        item(key = "m:${m.id}:user", contentType = "user-long") {
            LongUserPreview(m.content, style) { expanded.add(m.id) }
        }
    } else {
        // Texte complet: tranches paresseuses (une bulle découpée en items), jamais un seul Text géant.
        val chunks = LongText.chunks(m.content)
        for (c in chunks.indices) {
            item(key = "m:${m.id}:user:$c", contentType = "user-chunk") {
                LongUserChunk(
                    text = chunks[c].removeSuffix("\n"),
                    first = c == 0,
                    last = c == chunks.lastIndex,
                    style = style,
                    onCollapse = { expanded.remove(m.id) },
                )
            }
        }
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
    loader: MarkdownBlocksLoader,
    liveMarkdowns: LiveMarkdowns,
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
        thinkingItems(m, m.reasoning, mode, cb, thinkStyle, loader)
    }

    // 2. Corps de la réponse
    if (editing) {
        item(key = "m:${m.id}:edit", contentType = "edit") {
            EditBox(message = m, isUser = false, generating = ui.generating, cb = cb)
        }
        return
    }
    if (live) {
        val entry = liveMarkdowns.entry(m)
        // Lecture d'état: ce lambda ne se ré-exécute que lorsqu'un bloc se ferme (pas à chaque token).
        val stable = entry.stable.value
        markdownItems(
            key = "m:${m.id}:b",
            blocks = stable.blocks,
            style = style,
            modifier = Modifier.padding(horizontal = 16.dp),
            lastBlockSlices = stable.lastBlockSlices,
        )
        item(key = "m:${m.id}:live", contentType = "live") {
            LiveBody(entry, streaming, style)
        }
        return
    }
    if (m.content.isNotEmpty()) {
        markdownBody(
            key = "m:${m.id}:b",
            cacheKey = m.id,
            text = m.content,
            style = style,
            modifier = Modifier.padding(horizontal = 16.dp),
            loader = loader,
        )
    }

    // 3. Pied: erreur / interruption / méta + actions
    item(key = "m:${m.id}:foot", contentType = "foot") {
        AssistantFooter(ti, ui.generating, cb)
    }
}

/**
 * Texte markdown terminé dans la LazyColumn. Petit: un seul item, parsé à sa composition. Gros: un item par bloc
 * (laziness), blocs parsés hors thread principal ([loader]); texte brut tronqué en attendant.
 */
internal fun LazyListScope.markdownBody(
    key: String,
    cacheKey: Any,
    text: String,
    style: MarkdownStyle,
    modifier: Modifier,
    loader: MarkdownBlocksLoader,
) {
    if (text.length <= SMALL_MESSAGE_CHARS) {
        item(key = key, contentType = "md:msg") {
            MarkdownText(
                text = text,
                style = style,
                selectable = true,
                resetKey = cacheKey,
                modifier = modifier.padding(bottom = style.blockSpacing),
            )
        }
        return
    }
    val blocks = loader.blocks(cacheKey, text)
    if (blocks != null) {
        markdownItems(key = key, blocks = blocks, style = style, modifier = modifier)
    } else {
        item(key = "$key:wait", contentType = "md:wait") {
            Text(
                text = text.take(PLACEHOLDER_CHARS) + "…",
                style = style.body,
                maxLines = 14,
                overflow = TextOverflow.Clip,
                modifier = modifier.padding(bottom = style.blockSpacing),
            )
        }
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
