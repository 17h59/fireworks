package app.fwchat.ui.markdown

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Style du rendu markdown. Dérivé du thème Material (clair/sombre) par [rememberMarkdownStyle].
 * Les couleurs de texte laissées `Unspecified` suivent `LocalContentColor` du contexte.
 */
@Immutable
data class MarkdownStyle(
    val body: TextStyle,
    /** Styles des titres h1..h6 (index 0 = h1). */
    val headings: List<TextStyle>,
    val table: TextStyle,
    val code: TextStyle,
    val codeLabel: TextStyle,
    val inlineCode: SpanStyle,
    val link: SpanStyle,
    val mathInline: SpanStyle,
    val codeBackground: Color,
    val codeLabelColor: Color,
    val quoteBar: Color,
    val quoteContentAlpha: Float,
    val tableBorder: Color,
    val tableHeaderBackground: Color,
    val ruleColor: Color,
    val markerColor: Color,
    /** Espace entre deux blocs de haut niveau. */
    val blockSpacing: Dp = 12.dp,
    /** Espace entre deux items d'une liste serrée. */
    val tightSpacing: Dp = 4.dp,
    /** Espace supplémentaire au-dessus d'un titre (sauf s'il est le premier bloc). */
    val headingTopSpacing: Dp = 8.dp,
    /** true : un retour à la ligne simple dans un paragraphe reste un retour à la ligne (adapté au chat). */
    val softBreakAsNewline: Boolean = true,
)

/** Construit le style depuis le thème courant (mémoïsé tant que thème et [baseStyle] ne changent pas). */
@Composable
fun rememberMarkdownStyle(
    baseStyle: TextStyle = MaterialTheme.typography.bodyLarge,
): MarkdownStyle {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    return remember(colors, typography, baseStyle) { buildMarkdownStyle(colors, typography, baseStyle) }
}

internal fun buildMarkdownStyle(colors: ColorScheme, typography: Typography, baseStyle: TextStyle): MarkdownStyle {
    val body = baseStyle.copy(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.1.sp)
    fun h(size: Int, line: Int, weight: FontWeight, color: Color = Color.Unspecified) =
        body.copy(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, color = color)
    val headings = listOf(
        h(22, 30, FontWeight.Bold),
        h(20, 28, FontWeight.Bold),
        h(18, 26, FontWeight.SemiBold),
        h(16, 24, FontWeight.Bold),
        h(16, 24, FontWeight.SemiBold),
        h(14, 22, FontWeight.SemiBold, colors.onSurfaceVariant),
    )
    val codeBg = colors.surfaceContainerHighest.copy(alpha = 0.55f)
    return MarkdownStyle(
        body = body,
        headings = headings,
        table = body.copy(fontSize = 14.sp, lineHeight = 20.sp),
        code = body.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            letterSpacing = 0.sp,
        ),
        codeLabel = typography.labelSmall.copy(letterSpacing = 0.4.sp),
        inlineCode = SpanStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 0.9.em,
            background = codeBg,
        ),
        link = SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline),
        mathInline = SpanStyle(),
        codeBackground = codeBg,
        codeLabelColor = colors.onSurfaceVariant,
        quoteBar = colors.outlineVariant,
        quoteContentAlpha = 0.78f,
        tableBorder = colors.outlineVariant,
        tableHeaderBackground = colors.surfaceContainerHighest.copy(alpha = 0.4f),
        ruleColor = colors.outlineVariant,
        markerColor = colors.onSurfaceVariant,
    )
}
