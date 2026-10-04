package app.fwchat.ui.markdown

import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

/** Construit l'AnnotatedString d'une suite d'inlines (à mémoïser avec `remember`). */
internal fun buildInlineText(
    inlines: List<MdInline>,
    style: MarkdownStyle,
    uriHandler: UriHandler?,
): AnnotatedString = buildAnnotatedString {
    appendInlines(inlines, style, uriHandler, inLink = false)
}

private fun isSafeUrl(url: String): Boolean =
    url.startsWith("http://", true) || url.startsWith("https://", true) ||
        url.startsWith("mailto:", true) || url.startsWith("tel:", true)

private val BoldStyle = SpanStyle(fontWeight = FontWeight.Bold)
private val ItalicStyle = SpanStyle(fontStyle = FontStyle.Italic)
private val StrikeStyle = SpanStyle(textDecoration = TextDecoration.LineThrough)

private fun AnnotatedString.Builder.appendLink(
    url: String,
    style: MarkdownStyle,
    uriHandler: UriHandler?,
    inLink: Boolean,
    content: AnnotatedString.Builder.() -> Unit,
) {
    if (inLink || url.isEmpty() || !isSafeUrl(url)) {
        withStyle(style.link) { content() }
        return
    }
    val listener = if (uriHandler == null) null else LinkInteractionListener {
        // aucune appli pour ouvrir le lien : on ignore silencieusement
        runCatching { uriHandler.openUri(url) }
    }
    withLink(LinkAnnotation.Url(url, TextLinkStyles(style = style.link), listener)) { content() }
}

private fun AnnotatedString.Builder.appendInlines(
    inlines: List<MdInline>,
    style: MarkdownStyle,
    uriHandler: UriHandler?,
    inLink: Boolean,
) {
    for (node in inlines) {
        when (node) {
            is MdText -> append(node.text)
            is MdStrong -> withStyle(BoldStyle) { appendInlines(node.children, style, uriHandler, inLink) }
            is MdEmphasis -> withStyle(ItalicStyle) { appendInlines(node.children, style, uriHandler, inLink) }
            is MdStrike -> withStyle(StrikeStyle) { appendInlines(node.children, style, uriHandler, inLink) }
            is MdCode -> withStyle(style.inlineCode) { append(node.code) }
            is MdLink -> appendLink(node.url, style, uriHandler, inLink) {
                appendInlines(node.children, style, uriHandler, true)
            }
            is MdImage -> appendLink(node.url, style, uriHandler, inLink) {
                append(if (node.alt.isBlank()) "[Image]" else "[Image : ${node.alt}]")
            }
            is MdMath -> withStyle(style.mathInline) { append(node.raw) }
            MdLineBreak -> append('\n')
            MdSoftBreak -> append(if (style.softBreakAsNewline) '\n' else ' ')
        }
    }
}
