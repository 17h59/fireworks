package app.fwchat.ui.markdown

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ----------------------------------------------------------------------------------------------
// Briques de rendu partagées par `markdownItems` (items paresseux) et `MarkdownText` (Column).
// Aucun travail lourd dans la composition : les AnnotatedString sont mémoïsés par contenu.
// ----------------------------------------------------------------------------------------------

@Composable
internal fun rememberInlineText(inlines: List<MdInline>, style: MarkdownStyle): AnnotatedString {
    val uriHandler = LocalUriHandler.current
    return remember(inlines, style, uriHandler) { buildInlineText(inlines, style, uriHandler) }
}

/** Rend un bloc de haut niveau ou imbriqué. [depth] = niveau d'imbrication de listes (choix de la puce). */
@Composable
internal fun MdBlockContent(block: MdBlock, style: MarkdownStyle, depth: Int = 0, modifier: Modifier = Modifier) {
    when (block) {
        is MdParagraph -> MdParagraphView(block.inlines, style, modifier)
        is MdHeading -> MdHeadingView(block, style, modifier)
        is MdCodeBlock -> MdCodeView(block, 0, block.lineStarts.size, style, modifier)
        is MdMathBlock -> MdMathView(block, style, modifier)
        is MdQuote -> MdQuoteView(block, style, depth, modifier)
        is MdList -> MdListView(block, style, depth, modifier)
        is MdTable -> MdTableView(block, 0, block.rows.size, true, style, modifier)
        MdRule -> MdRuleView(style, modifier)
    }
}

/** Suite de blocs séparés par l'espacement standard (utilisé pour les contenus imbriqués). */
@Composable
internal fun MdBlockColumn(
    blocks: List<MdBlock>,
    style: MarkdownStyle,
    depth: Int,
    spacing: Dp,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        for (b in blocks) MdBlockContent(b, style, depth)
    }
}

@Composable
internal fun MdParagraphView(inlines: List<MdInline>, style: MarkdownStyle, modifier: Modifier = Modifier) {
    if (inlines.isEmpty()) return
    Text(text = rememberInlineText(inlines, style), style = style.body, modifier = modifier)
}

@Composable
internal fun MdHeadingView(block: MdHeading, style: MarkdownStyle, modifier: Modifier = Modifier) {
    if (block.inlines.isEmpty()) return
    Text(
        text = rememberInlineText(block.inlines, style),
        style = style.headings[(block.level - 1).coerceIn(0, 5)],
        modifier = modifier.semantics { heading() },
    )
}

@Composable
internal fun MdRuleView(style: MarkdownStyle, modifier: Modifier = Modifier) {
    HorizontalDivider(modifier.padding(vertical = 4.dp), thickness = 1.dp, color = style.ruleColor)
}

// ------------------------------------------------------------------ code

/**
 * Bloc de code (ou un morceau [fromLine, toLine) d'un gros bloc de code). Défilement horizontal, pas de
 * retour à la ligne forcé. [showHeader] : libellé du langage + bouton copier (copie TOUT le bloc).
 */
@Composable
internal fun MdCodeView(
    block: MdCodeBlock,
    fromLine: Int,
    toLine: Int,
    style: MarkdownStyle,
    modifier: Modifier = Modifier,
    showHeader: Boolean = fromLine == 0,
    roundTop: Boolean = fromLine == 0,
    roundBottom: Boolean = toLine >= block.lineStarts.size,
) {
    val text = remember(block, fromLine, toLine) { codeSlice(block, fromLine, toLine) }
    val shape: Shape = RoundedCornerShape(
        topStart = if (roundTop) 8.dp else 0.dp, topEnd = if (roundTop) 8.dp else 0.dp,
        bottomStart = if (roundBottom) 8.dp else 0.dp, bottomEnd = if (roundBottom) 8.dp else 0.dp,
    )
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(style.codeBackground),
    ) {
        if (showHeader) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = block.language.orEmpty(),
                    style = style.codeLabel,
                    color = style.codeLabelColor,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                CopyButton(text = block.code, tint = style.codeLabelColor)
            }
        }
        Box(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = if (showHeader) 4.dp else 8.dp)
                .padding(bottom = if (showHeader) 6.dp else 0.dp),
        ) {
            Text(text = text, style = style.code, softWrap = false)
        }
    }
}

private fun codeSlice(block: MdCodeBlock, fromLine: Int, toLine: Int): String {
    val starts = block.lineStarts
    val code = block.code
    if (fromLine <= 0 && toLine >= starts.size) return code
    val from = starts[fromLine.coerceIn(0, starts.size - 1)]
    val end = if (toLine >= starts.size) code.length else starts[toLine] - 1 // sans le '\n' final
    return code.substring(from, end.coerceAtLeast(from))
}

@Composable
private fun CopyButton(text: String, tint: androidx.compose.ui.graphics.Color) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = "Copier le code") {
                scope.launch {
                    runCatching { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("code", text))) }
                    copied = true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (copied) Icons.Filled.Check else Icons.Outlined.ContentCopy,
            contentDescription = if (copied) "Copié" else "Copier le code",
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
internal fun MdMathView(block: MdMathBlock, style: MarkdownStyle, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp)) {
        Text(text = block.text, style = style.code.copy(fontSize = style.body.fontSize), softWrap = false)
    }
}

// ------------------------------------------------------------------ citations

@Composable
internal fun MdQuoteView(block: MdQuote, style: MarkdownStyle, depth: Int, modifier: Modifier = Modifier) {
    QuoteFrame(style, bottomInset = 0.dp, modifier = modifier) {
        MdBlockColumn(block.blocks, style, depth, style.blockSpacing / 2)
    }
}

/** Cadre de citation : barre verticale fine à gauche, texte légèrement atténué. */
@Composable
internal fun QuoteFrame(
    style: MarkdownStyle,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val barWidth = 3.dp
    val bar = style.quoteBar
    val contentColor = LocalContentColor.current.let { it.copy(alpha = it.alpha * style.quoteContentAlpha) }
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Box(
            modifier
                .fillMaxWidth()
                .drawBehind {
                    val w = barWidth.toPx()
                    drawRect(bar, topLeft = Offset.Zero, size = Size(w, (size.height - bottomInset.toPx()).coerceAtLeast(0f)))
                }
                .padding(start = 13.dp),
        ) { content() }
    }
}

// ------------------------------------------------------------------ listes

private val BULLETS = arrayOf("•", "◦", "▪")

internal fun markerWidth(list: MdList): Dp {
    if (!list.ordered) return 20.dp
    val digits = (list.start + list.items.size - 1).coerceAtLeast(1).toString().length
    return (12 + 9 * digits).dp
}

@Composable
internal fun MdListView(list: MdList, style: MarkdownStyle, depth: Int, modifier: Modifier = Modifier) {
    val gap = if (list.tight) style.tightSpacing else style.blockSpacing - 2.dp
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        for (i in list.items.indices) MdListItemView(list, i, list.items[i], style, depth)
    }
}

/** Un item de liste : puce / numéro / case à cocher alignés sur la première ligne, puis son contenu. */
@Composable
internal fun MdListItemView(list: MdList, index: Int, item: MdListItem, style: MarkdownStyle, depth: Int, modifier: Modifier = Modifier) {
    val lineHeight = with(LocalDensity.current) { style.body.lineHeight.toDp() }
    Row(modifier) {
        Box(Modifier.width(markerWidth(list)).height(lineHeight), contentAlignment = Alignment.CenterStart) {
            when {
                item.checked != null -> Icon(
                    imageVector = if (item.checked) Icons.Filled.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                    contentDescription = if (item.checked) "Fait" else "À faire",
                    tint = style.markerColor,
                    modifier = Modifier.size(18.dp),
                )
                list.ordered -> Text(
                    text = "${list.start + index}.",
                    style = style.body,
                    color = style.markerColor,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth().padding(end = 6.dp),
                )
                else -> Text(
                    text = BULLETS[depth % BULLETS.size],
                    style = style.body,
                    color = style.markerColor,
                )
            }
        }
        MdBlockColumn(
            item.blocks, style, depth + 1,
            spacing = if (list.tight) 2.dp else style.blockSpacing / 2,
            modifier = Modifier.weight(1f),
        )
    }
}

// ------------------------------------------------------------------ tableaux

/**
 * Tableau GFM (ou morceau de lignes [fromRow, toRow)). Largeurs de colonnes estimées sur tout le tableau
 * (donc identiques d'un morceau à l'autre), cellules longues renvoyées à la ligne, défilement horizontal
 * si le tableau dépasse la largeur disponible.
 */
@Composable
internal fun MdTableView(
    table: MdTable,
    fromRow: Int,
    toRow: Int,
    showHeader: Boolean,
    style: MarkdownStyle,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val density = LocalDensity.current
    val cols = table.aligns.size
    if (cols == 0) return
    val colWidths = remember(table, style, density) {
        val charWidth = with(density) { style.table.fontSize.toDp() } * 0.56f
        val chars = table.colChars
        List(cols) { c -> charWidth * chars[c].coerceAtLeast(3) + 20.dp }
    }
    val cells = remember(table, fromRow, toRow, showHeader, style, uriHandler) {
        val out = ArrayList<List<AnnotatedString>>()
        if (showHeader) out.add(table.header.map { buildInlineText(it, style, uriHandler) })
        for (r in fromRow until toRow.coerceAtMost(table.rows.size)) {
            out.add(table.rows[r].map { buildInlineText(it, style, uriHandler) })
        }
        out
    }
    val border = style.tableBorder
    val headerBg = style.tableHeaderBackground
    Box(modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Column(
            Modifier.drawBehind {
                val sw = 1.dp.toPx()
                drawRect(border, Offset.Zero, Size(sw, size.height)) // bord gauche
                if (showHeader) drawRect(border, Offset.Zero, Size(size.width, sw)) // bord haut
            },
        ) {
            for ((rowIdx, row) in cells.withIndex()) {
                val isHeader = showHeader && rowIdx == 0
                Row(Modifier.height(IntrinsicSize.Min)) {
                    for (c in 0 until cols) {
                        val align = when (table.aligns[c]) {
                            MdAlign.Center -> TextAlign.Center
                            MdAlign.Right -> TextAlign.End
                            else -> TextAlign.Start
                        }
                        Box(
                            Modifier
                                .width(colWidths[c])
                                .fillMaxHeight()
                                .then(if (isHeader) Modifier.background(headerBg) else Modifier)
                                .drawBehind {
                                    val sw = 1.dp.toPx()
                                    drawRect(border, Offset(size.width - sw, 0f), Size(sw, size.height))
                                    drawRect(border, Offset(0f, size.height - sw), Size(size.width, sw))
                                }
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                        ) {
                            val t = row.getOrNull(c)
                            if (t != null && t.isNotEmpty()) {
                                Text(
                                    text = t,
                                    style = if (isHeader) style.table.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) else style.table,
                                    textAlign = align,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
