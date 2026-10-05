package app.fwchat.ui.markdown

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 * @param lastBlockSlices nombre maximal de tranches émises pour le DERNIER bloc de [blocks] (voir [lazySliceCount]) ;
 *   0 = aucune. Sert au streaming : un gros bloc encore ouvert n'émet que ses tranches déjà complètes
 *   (le reste est rendu à part par [MarkdownTail]). Les clés restent celles du rendu final.
 */
fun LazyListScope.markdownItems(
    key: String,
    blocks: List<MdBlock>,
    style: MarkdownStyle? = null,
    modifier: Modifier = Modifier,
    selectable: Boolean = true,
    lastBlockSlices: Int = Int.MAX_VALUE,
) {
    for (index in blocks.indices) {
        val block = blocks[index]
        val k = "$key:$index"
        val limit = if (index == blocks.lastIndex) lastBlockSlices else Int.MAX_VALUE
        if (limit <= 0) continue
        when (block) {
            is MdParagraph -> {
                val chunks = block.chunks
                for (c in 0 until minOf(chunks.size, limit)) {
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
                for (c in 0 until minOf(maxOf(n, 1), limit)) {
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
                for (i in 0 until minOf(items.size, limit)) {
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
                for (i in 0 until minOf(children.size, limit)) {
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
                for (c in 0 until minOf(maxOf(n, 1), limit)) {
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

/**
 * Nombre d'items (« tranches ») que [markdownItems] émet pour [block] : paragraphe géant coupé en morceaux, code par
 * 80 lignes, tableau par 40 lignes, un item par élément de liste, un par bloc d'une citation, 1 sinon.
 */
fun lazySliceCount(block: MdBlock): Int = when (block) {
    is MdParagraph -> block.chunks.size
    is MdCodeBlock -> maxOf((block.lineStarts.size + CODE_CHUNK_LINES - 1) / CODE_CHUNK_LINES, 1)
    is MdList -> block.items.size
    is MdQuote -> block.blocks.size
    is MdTable -> maxOf((block.rows.size + TABLE_CHUNK_ROWS - 1) / TABLE_CHUNK_ROWS, 1)
    is MdHeading, is MdMathBlock, MdRule -> 1
}

/**
 * Rend les tranches [fromSlice, fin) de [block] dans UN seul item (Column) : la partie « vivante » d'un bloc encore
 * ouvert pendant le streaming, dont les tranches précédentes ont déjà été émises par [markdownItems]
 * (`lastBlockSlices = fromSlice`). Pas de `SelectionContainer` (texte en mouvement).
 * Ne compose que les tranches demandées : un bloc de code de 10 000 lignes ne coûte que ses ≤ 80 dernières lignes.
 */
@Composable
fun MarkdownTail(
    block: MdBlock,
    fromSlice: Int,
    style: MarkdownStyle,
    modifier: Modifier = Modifier,
) {
    val from = fromSlice.coerceAtLeast(0)
    Column(modifier) {
        when (block) {
            is MdParagraph -> {
                val chunks = block.chunks
                for (c in from until chunks.size) MdParagraphView(chunks[c], style)
            }
            is MdCodeBlock -> {
                val lines = block.lineStarts.size
                MdCodeView(block, minOf(from * CODE_CHUNK_LINES, lines - 1).coerceAtLeast(0), lines, style)
            }
            is MdTable -> {
                val rows = block.rows.size
                val fromRow = minOf(from * TABLE_CHUNK_ROWS, rows)
                MdTableView(block, fromRow, rows, showHeader = from == 0, style = style)
            }
            is MdList -> for (i in from until block.items.size) {
                val gap = if (block.tight) style.tightSpacing else style.blockSpacing - 2.dp
                Box(Modifier.padding(bottom = if (i == block.items.lastIndex) 0.dp else gap)) {
                    MdListItemView(block, i, block.items[i], style, 0)
                }
            }
            is MdQuote -> QuoteFrame(style, bottomInset = 0.dp) {
                Column {
                    for (i in from until block.blocks.size) {
                        Box(Modifier.padding(bottom = if (i == block.blocks.lastIndex) 0.dp else style.blockSpacing / 2)) {
                            MdBlockContent(block.blocks[i], style, 0)
                        }
                    }
                }
            }
            else -> MdBlockContent(block, style)
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
 * @param maxLines si fini : aperçu des [maxLines] PREMIÈRES lignes (un seul `Text(maxLines, Ellipsis)` sur le début
 *   du texte aplati : ne peut pas déborder de sa boîte et ne dépend que du début, donc fixe pendant le streaming).
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
    selectable: Boolean = false,
    color: Color = Color.Unspecified,
    resetKey: Any? = null,
) {
    val all = rememberMarkdownBlocks(text, streaming, resetKey)
    val limited = maxLines != Int.MAX_VALUE
    val content: @Composable () -> Unit = {
        val colored: @Composable () -> Unit = {
            if (limited) {
                MarkdownPreview(all, style, maxLines)
            } else {
                Column { MdBlockColumn(all, style, depth = 0, spacing = style.blockSpacing) }
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

/**
 * Aperçu : les [maxLines] premières lignes. Le début est mémoïsé sur ses blocs (égalité structurelle) : tant que
 * le début ne change pas, l'aperçu n'est ni recalculé ni recomposé, même si la fin du texte continue d'arriver.
 * Un vrai `Text(maxLines)` : la hauteur est bornée par le texte lui-même, rien ne peut déborder sur les voisins.
 */
@Composable
private fun MarkdownPreview(all: List<MdBlock>, style: MarkdownStyle, maxLines: Int) {
    val uriHandler = LocalUriHandler.current
    val head = remember(all, maxLines) { previewBlocks(all, maxLines) }
    val annotated = remember(head, style, uriHandler) { previewAnnotated(head, style, uriHandler) }
    Text(text = annotated, style = style.body, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** Ne garde que les blocs nécessaires pour remplir environ [maxLines] lignes (évite de composer tout un long texte). */
internal fun previewBlocks(blocks: List<MdBlock>, maxLines: Int): List<MdBlock> {
    if (blocks.size <= 1) return blocks
    val want = maxLines + 2
    var acc = 0
    val out = ArrayList<MdBlock>()
    for (b in blocks) {
        out.add(b)
        acc += estimateLines(b)
        if (acc >= want) break
    }
    return out
}

/** Aplatit des blocs en un seul texte (un bloc ou un item de liste par ligne) pour l'aperçu à lignes fixes. */
internal fun previewAnnotated(blocks: List<MdBlock>, style: MarkdownStyle, uriHandler: UriHandler?): AnnotatedString =
    buildAnnotatedString { appendPreviewBlocks(blocks, style, uriHandler, sameLine = false) }

private fun AnnotatedString.Builder.appendPreviewBlocks(
    blocks: List<MdBlock>,
    style: MarkdownStyle,
    uriHandler: UriHandler?,
    sameLine: Boolean,
) {
    var joined = sameLine // le premier bloc se place à la suite de la puce d'un item de liste
    fun newLine() {
        if (joined) joined = false else if (length > 0) append('\n')
    }
    for (b in blocks) when (b) {
        is MdParagraph -> if (b.inlines.isNotEmpty()) {
            newLine()
            append(buildInlineText(b.inlines, style, uriHandler))
        }
        is MdHeading -> if (b.inlines.isNotEmpty()) {
            newLine()
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(buildInlineText(b.inlines, style, uriHandler)) }
        }
        is MdCodeBlock -> {
            newLine()
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(b.code.trimEnd('\n')) }
        }
        is MdMathBlock -> {
            newLine()
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(b.text.trim()) }
        }
        is MdQuote -> {
            newLine()
            appendPreviewBlocks(b.blocks, style, uriHandler, sameLine = true)
        }
        is MdList -> {
            b.items.forEachIndexed { i, item ->
                newLine()
                append(if (b.ordered) "${b.start + i}. " else "• ")
                appendPreviewBlocks(item.blocks, style, uriHandler, sameLine = true)
            }
        }
        is MdTable -> {
            newLine()
            append(b.header.joinToString(" | ") { plainText(it) })
        }
        MdRule -> Unit
    }
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
