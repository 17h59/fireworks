package app.fwchat.ui.markdown

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// ----------------------------------------------------------------------------------------------
// API publique de rendu : markdownItems (paresseux), MarkdownText (petit texte), rememberMarkdownBlocks.
// ----------------------------------------------------------------------------------------------

private const val ASYNC_PARSE_THRESHOLD_CHARS = 60_000

/**
 * Parse [text] en blocs, de façon incrémentale et mémoïsée.
 *
 * - Pendant le streaming (`streaming = true`) : seul le dernier bloc est re-parsé à chaque nouveau texte
 *   (le texte doit seulement grandir) ; les blocs fermés gardent la même identité.
 * - Une fois le flux terminé, rappeler avec `streaming = false` (rendu définitif).
 * - Texte statique très long (> 60 000 caractères, `streaming = false`) : parsé hors du thread principal
 *   (la liste est vide, ou garde l'état précédent, jusqu'à la fin du parse).
 *
 * @param resetKey identifie le message (ex. son id) ; change => nouvel état incrémental.
 */
@Composable
fun rememberMarkdownBlocks(text: String, streaming: Boolean = false, resetKey: Any? = null): List<MdBlock> {
    val inc = remember(resetKey) { IncrementalMarkdown(streaming) }
    if (streaming || text.length <= ASYNC_PARSE_THRESHOLD_CHARS) {
        return remember(inc, text, streaming) { inc.update(text, streaming) }
    }
    val state = produceState(initialValue = inc.blocks, text) {
        value = withContext(Dispatchers.Default) { parseMarkdown(text) }
    }
    return state.value
}

/**
 * Émet UN item paresseux par bloc de haut niveau (et plusieurs pour les très gros blocs : paragraphe géant,
 * code de plus de 80 lignes, tableau de plus de 40 lignes, chaque item de liste, chaque bloc d'une citation),
 * pour que seuls les morceaux visibles soient composés dans la `LazyColumn`.
 *
 * Clés : `"$key:$index"` (ou `"$key:$index:$sub"` pour les sous-items) : stables pendant le streaming.
 * Chaque item porte lui-même son espacement bas : ne PAS utiliser `Arrangement.spacedBy` pour les items markdown
 * (un espacement entre messages se met dans l'item parent / un `Spacer` / `contentPadding`).
 *
 * @param key préfixe unique par message (ex. `"msg-${id}"`).
 * @param style obtenu par [rememberMarkdownStyle] (à créer UNE fois, hors du lambda de la LazyColumn) ; null = défaut.
 * @param selectable true : chaque bloc est sélectionnable/copiable individuellement. Pour une sélection qui
 *   traverse plusieurs blocs/messages, passer false et entourer la LazyColumn d'un seul `SelectionContainer`.
 */
fun LazyListScope.markdownItems(
    key: String,
    blocks: List<MdBlock>,
    style: MarkdownStyle? = null,
    modifier: Modifier = Modifier,
    selectable: Boolean = true,
) {
    for (index in blocks.indices) {
        val block = blocks[index]
        val k = "$key:$index"
        when (block) {
            is MdParagraph -> {
                val chunks = block.chunks
                for (c in chunks.indices) {
                    val inl = chunks[c]
                    val last = c == chunks.lastIndex
                    item(key = if (chunks.size == 1) k else "$k:$c", contentType = "md:p") {
                        val st = style ?: rememberMarkdownStyle()
                        Frame(selectable, modifier, bottom = if (last) st.blockSpacing else 0.dp) {
                            MdParagraphView(inl, st)
                        }
                    }
                }
            }
            is MdHeading -> item(key = k, contentType = "md:h") {
                val st = style ?: rememberMarkdownStyle()
                Frame(selectable, modifier, top = if (index > 0) st.headingTopSpacing else 0.dp, bottom = st.blockSpacing) {
                    MdHeadingView(block, st)
                }
            }
            is MdCodeBlock -> {
                val lines = block.lineStarts.size
                val n = (lines + CODE_CHUNK_LINES - 1) / CODE_CHUNK_LINES
                for (c in 0 until maxOf(n, 1)) {
                    val from = c * CODE_CHUNK_LINES
                    val to = if (n <= 1) lines else minOf(lines, from + CODE_CHUNK_LINES)
                    val last = c >= n - 1
                    item(key = if (n <= 1) k else "$k:$c", contentType = "md:code") {
                        val st = style ?: rememberMarkdownStyle()
                        Frame(selectable, modifier, bottom = if (last) st.blockSpacing else 0.dp) {
                            MdCodeView(block, from, to, st)
                        }
                    }
                }
            }
            is MdMathBlock -> item(key = k, contentType = "md:math") {
                val st = style ?: rememberMarkdownStyle()
                Frame(selectable, modifier, bottom = st.blockSpacing) { MdMathView(block, st) }
            }
            is MdList -> {
                val items = block.items
                for (i in items.indices) {
                    val last = i == items.lastIndex
                    item(key = "$k:$i", contentType = "md:li") {
                        val st = style ?: rememberMarkdownStyle()
                        val gap = if (last) st.blockSpacing else if (block.tight) st.tightSpacing else st.blockSpacing - 2.dp
                        Frame(selectable, modifier, bottom = gap) { MdListItemView(block, i, items[i], st, 0) }
                    }
                }
            }
            is MdQuote -> {
                val children = block.blocks
                for (i in children.indices) {
                    val last = i == children.lastIndex
                    item(key = "$k:$i", contentType = "md:q") {
                        val st = style ?: rememberMarkdownStyle()
                        // la barre couvre l'espace entre blocs (continuité) mais pas l'espacement final
                        Frame(selectable, modifier, bottom = if (last) st.blockSpacing else 0.dp) {
                            QuoteFrame(st, bottomInset = 0.dp) {
                                Box(Modifier.padding(bottom = if (last) 0.dp else st.blockSpacing / 2)) {
                                    MdBlockContent(children[i], st, 0)
                                }
                            }
                        }
                    }
                }
            }
            is MdTable -> {
                val rows = block.rows.size
                val n = (rows + TABLE_CHUNK_ROWS - 1) / TABLE_CHUNK_ROWS
                for (c in 0 until maxOf(n, 1)) {
                    val from = c * TABLE_CHUNK_ROWS
                    val to = if (n <= 1) rows else minOf(rows, from + TABLE_CHUNK_ROWS)
                    val last = c >= n - 1
                    item(key = if (n <= 1) k else "$k:$c", contentType = "md:table") {
                        val st = style ?: rememberMarkdownStyle()
                        Frame(selectable, modifier, bottom = if (last) st.blockSpacing else 0.dp) {
                            MdTableView(block, from, to, showHeader = c == 0, style = st)
                        }
                    }
                }
            }
            MdRule -> item(key = k, contentType = "md:hr") {
                val st = style ?: rememberMarkdownStyle()
                Frame(selectable, modifier, bottom = st.blockSpacing) { MdRuleView(st) }
            }
        }
    }
}

@Composable
private fun Frame(
    selectable: Boolean,
    modifier: Modifier,
    top: Dp = 0.dp,
    bottom: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    val body: @Composable () -> Unit = {
        Box(modifier.padding(top = top, bottom = bottom)) { content() }
    }
    if (selectable) SelectionContainer { body() } else body()
}

/**
 * Version non paresseuse pour les petits textes (aperçu de thinking, libellés, messages courts).
 * Tous les blocs sont composés dans une `Column` : ne pas l'utiliser pour de longues réponses
 * (utiliser [markdownItems] dans la LazyColumn).
 *
 * @param streaming true tant que [text] est en cours de génération (parse incrémental, marqueurs non fermés lissés).
 * @param maxLines si fini : aperçu limité à ce nombre de lignes de corps (hauteur plafonnée et rognée, sans
 *   composer tout le texte). [previewFromEnd] = true montre la FIN du texte (idéal pour un thinking en cours).
 * @param color couleur du texte (Unspecified = couleur de contenu courante).
 * @param selectable true : texte sélectionnable (par bloc).
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: MarkdownStyle = rememberMarkdownStyle(),
    streaming: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    previewFromEnd: Boolean = false,
    selectable: Boolean = false,
    color: Color = Color.Unspecified,
    resetKey: Any? = null,
) {
    val all = rememberMarkdownBlocks(text, streaming, resetKey)
    val limited = maxLines != Int.MAX_VALUE
    val blocks = if (limited) remember(all, maxLines, previewFromEnd) { previewBlocks(all, maxLines, previewFromEnd) } else all
    val content: @Composable () -> Unit = {
        val colored: @Composable () -> Unit = {
            Column(modifier = if (limited) Modifier.previewClip(style, maxLines, previewFromEnd) else Modifier) {
                MdBlockColumn(blocks, style, depth = 0, spacing = style.blockSpacing)
            }
        }
        if (color != Color.Unspecified) {
            CompositionLocalProvider(LocalContentColor provides color) { colored() }
        } else {
            colored()
        }
    }
    Box(modifier) {
        if (selectable) SelectionContainer { content() } else content()
    }
}

/** Plafonne la hauteur à [maxLines] lignes de corps et rogne le surplus (début ou fin du contenu visible). */
private fun Modifier.previewClip(style: MarkdownStyle, maxLines: Int, fromEnd: Boolean): Modifier =
    this.layout { measurable, constraints ->
        val maxPx = (style.body.lineHeight.toPx() * maxLines).roundToInt().coerceAtLeast(0)
        val p = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val h = minOf(p.height, maxPx, constraints.maxHeight)
        layout(p.width, h) { p.place(0, if (fromEnd) h - p.height else 0) }
    }.clipToBounds()

/** Ne garde que les blocs nécessaires pour remplir environ [maxLines] lignes (évite de composer tout un long texte). */
internal fun previewBlocks(blocks: List<MdBlock>, maxLines: Int, fromEnd: Boolean): List<MdBlock> {
    if (blocks.size <= 1) return blocks
    val want = maxLines + 2
    var acc = 0
    val out = ArrayList<MdBlock>()
    val order = if (fromEnd) blocks.indices.reversed() else blocks.indices
    for (i in order) {
        out.add(blocks[i])
        acc += estimateLines(blocks[i])
        if (acc >= want) break
    }
    if (fromEnd) out.reverse()
    return out
}

private fun estimateLines(b: MdBlock): Int = when (b) {
    is MdParagraph -> 1 + inlineLength(b.inlines) / 38
    is MdHeading -> 1 + inlineLength(b.inlines) / 30
    is MdCodeBlock -> 1 + b.lineStarts.size
    is MdMathBlock -> 1 + b.text.count { it == '\n' }
    is MdQuote -> b.blocks.sumOf { estimateLines(it) }
    is MdList -> b.items.sumOf { it.blocks.sumOf { x -> estimateLines(x) } }
    is MdTable -> 2 + b.rows.size
    MdRule -> 1
}
