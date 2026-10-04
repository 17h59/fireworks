package app.fwchat.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.fwchat.domain.Message
import app.fwchat.domain.MessageStatus
import app.fwchat.domain.StreamingText
import app.fwchat.domain.ThreadItem
import app.fwchat.ui.common.formatInt
import app.fwchat.ui.markdown.MarkdownBlocksCache
import app.fwchat.ui.markdown.MarkdownStyle
import app.fwchat.ui.markdown.MarkdownText
import app.fwchat.ui.markdown.markdownItems

/** Au-delà, un message utilisateur est affiché en texte brut (évite de composer des milliers de blocs dans une bulle). */
private const val USER_MARKDOWN_MAX_CHARS = 20_000

/** Limite la largeur à [fraction] de la largeur disponible (bulle utilisateur ≈ 85 %). */
private fun Modifier.maxWidthFraction(fraction: Float): Modifier = layout { measurable, constraints ->
    val limit = (constraints.maxWidth * fraction).toInt().coerceAtLeast(constraints.minWidth)
    val placeable = measurable.measure(constraints.copy(maxWidth = limit))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

// ------------------------------------------------------------------------------------------------
// Message utilisateur

@Composable
internal fun UserBubble(text: String, style: MarkdownStyle) {
    Box(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
            modifier = Modifier.maxWidthFraction(0.85f),
        ) {
            Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (text.length <= USER_MARKDOWN_MAX_CHARS) {
                    MarkdownText(text = text, style = style, selectable = true)
                } else {
                    SelectionContainer { Text(text, style = style.body) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Message assistant: corps en direct et pied

/** Corps du message en cours: lit le texte live DANS cet item (seul ce message se recompose à chaque token). */
@Composable
internal fun LiveBody(m: Message, streaming: State<Map<String, StreamingText>>, style: MarkdownStyle) {
    val live by rememberLiveText(m.id, streaming)
    val content = live?.content ?: m.content
    val reasoning = live?.reasoning ?: m.reasoning.orEmpty()
    when {
        content.isNotEmpty() -> Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            MarkdownText(text = content, style = style, streaming = true, resetKey = m.id)
        }
        reasoning.isEmpty() -> Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp).semantics { contentDescription = "Réponse en cours" },
                strokeWidth = 2.dp,
            )
        }
        else -> Unit // le bloc de réflexion montre déjà l'activité
    }
}

@Composable
internal fun AssistantFooter(ti: ThreadItem, generating: Boolean, cb: MessageCallbacks) {
    val m = ti.message
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        when (m.status) {
            MessageStatus.ERROR -> ErrorBlock(m, generating, cb)
            MessageStatus.INTERRUPTED -> Text(
                "Interrompu",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            else -> Unit
        }
        if (m.status != MessageStatus.ERROR) {
            ConversationFormatter.meta(m)?.let { meta ->
                Text(
                    meta,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        ActionsBar(ti, assistant = true, generating = generating, cb = cb)
    }
}

@Composable
private fun ErrorBlock(m: Message, generating: Boolean, cb: MessageCallbacks) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                m.error ?: "Une erreur est survenue.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp),
            )
            TextButton(onClick = { cb.onRegenerate(m.id) }, enabled = !generating) { Text("Réessayer") }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Barre d'actions

@Composable
internal fun ActionsBar(ti: ThreadItem, assistant: Boolean, generating: Boolean, cb: MessageCallbacks) {
    val m = ti.message
    Row(
        Modifier.fillMaxWidth().padding(horizontal = if (assistant) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (assistant) Arrangement.Start else Arrangement.End,
    ) {
        if (ti.siblingCount > 1) {
            val index = ti.siblingIndex
            ActionIcon(
                Icons.Filled.ChevronLeft, "Version précédente",
                enabled = !generating && index > 0, width = 36.dp,
            ) { cb.onSibling(ti, -1) }
            Text(
                "${index + 1}/${ti.siblingCount}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = "Version ${index + 1} sur ${ti.siblingCount}" },
            )
            ActionIcon(
                Icons.Filled.ChevronRight, "Version suivante",
                enabled = !generating && index < ti.siblingCount - 1, width = 36.dp,
            ) { cb.onSibling(ti, +1) }
            Spacer(Modifier.width(if (assistant) 6.dp else 4.dp))
        }
        ActionIcon(Icons.Outlined.ContentCopy, "Copier le message") { cb.onCopy(m.content) }
        ActionIcon(Icons.Outlined.Edit, "Modifier le message", enabled = !generating) { cb.onStartEdit(m.id) }
        if (assistant) {
            ActionIcon(Icons.Outlined.Refresh, "Régénérer la réponse", enabled = !generating) { cb.onRegenerate(m.id) }
        }
        ActionIcon(Icons.AutoMirrored.Outlined.CallSplit, "Forker la conversation ici") { cb.onFork(m.id) }
        ActionIcon(Icons.Outlined.Delete, "Supprimer le message", enabled = !generating) { cb.onDelete(m.id) }
    }
}

@Composable
private fun ActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean = true,
    width: Dp = 44.dp,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(width = width, height = 48.dp)) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
    }
}

// ------------------------------------------------------------------------------------------------
// Édition dans la liste

@Composable
internal fun EditBox(message: Message, isUser: Boolean, generating: Boolean, cb: MessageCallbacks) {
    var text by rememberSaveable(message.id) { mutableStateOf(message.content) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(message.id) {
        try {
            focus.requestFocus()
        } catch (_: IllegalStateException) {
            // Le champ n'est pas encore attaché: sans importance.
        }
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            minLines = 2,
            maxLines = 14,
            shape = RoundedCornerShape(16.dp),
            textStyle = MaterialTheme.typography.bodyLarge,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = cb.onCancelEdit) { Text("Annuler") }
            if (isUser) {
                OutlinedButton(
                    onClick = { cb.onSaveEdit(message.id, text) },
                    enabled = text.isNotBlank(),
                    modifier = Modifier.heightIn(min = 40.dp),
                ) { Text("Enregistrer", style = MaterialTheme.typography.labelMedium) }
                Button(
                    onClick = { cb.onSendEdit(message.id, text) },
                    enabled = text.isNotBlank() && !generating,
                    modifier = Modifier.heightIn(min = 52.dp),
                ) { Text("Envoyer", style = MaterialTheme.typography.titleMedium) }
            } else {
                Button(
                    onClick = { cb.onSaveEdit(message.id, text) },
                    enabled = text.isNotBlank(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Enregistrer") }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Bloc de réflexion (thinking)

/** Réflexion d'un message en cours: texte lu DANS cet item. Invisible tant qu'aucune réflexion n'est arrivée. */
@Composable
internal fun LiveThinking(
    m: Message,
    streaming: State<Map<String, StreamingText>>,
    mode: ThinkingMode,
    cb: MessageCallbacks,
    thinkStyle: MarkdownStyle,
) {
    val live by rememberLiveText(m.id, streaming)
    val reasoning = live?.reasoning ?: m.reasoning.orEmpty()
    if (reasoning.isEmpty()) return
    val content = live?.content ?: m.content
    val thinking = ThinkingStates.isThinking(MessageStatus.STREAMING, content)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp)) {
        ThinkingHeader(mode, thinking, tokens = null) { cb.onThinkingToggle(m.id, mode) }
        when (mode) {
            ThinkingMode.COLLAPSED -> Unit
            ThinkingMode.PREVIEW -> {
                ThinkingText(m.id, reasoning, thinkStyle, streaming = thinking, preview = true, fromEnd = true)
                ShowAllButton(reasoning) { cb.onThinkingShowAll(m.id, mode) }
            }
            ThinkingMode.FULL -> {
                ThinkingText(m.id, reasoning, thinkStyle, streaming = thinking, preview = false, fromEnd = false)
                ShrinkButton { cb.onThinkingShrink(m.id, mode) }
            }
        }
    }
}

/**
 * Réflexion d'un message terminé. Replié: en-tête seul. Aperçu: 5 premières lignes. Complet: rendu paresseux
 * par blocs (une réflexion peut être très longue).
 */
internal fun LazyListScope.thinkingItems(
    m: Message,
    reasoning: String,
    mode: ThinkingMode,
    cb: MessageCallbacks,
    thinkStyle: MarkdownStyle,
    cache: MarkdownBlocksCache,
) {
    item(key = "m:${m.id}:think", contentType = "think") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp)) {
            ThinkingHeader(mode, thinking = false, tokens = m.reasoningTokens) { cb.onThinkingToggle(m.id, mode) }
            if (mode == ThinkingMode.PREVIEW) {
                ThinkingText(m.id, reasoning, thinkStyle, streaming = false, preview = true, fromEnd = false)
                ShowAllButton(reasoning) { cb.onThinkingShowAll(m.id, mode) }
            }
        }
    }
    if (mode == ThinkingMode.FULL) {
        markdownItems(
            key = "m:${m.id}:r",
            blocks = cache.blocks("r:${m.id}", reasoning),
            style = thinkStyle,
            modifier = Modifier.padding(start = 28.dp, end = 16.dp),
        )
        item(key = "m:${m.id}:r-shrink", contentType = "think-shrink") {
            Box(Modifier.padding(start = 16.dp)) { ShrinkButton { cb.onThinkingShrink(m.id, mode) } }
        }
    }
}

@Composable
private fun ThinkingHeader(mode: ThinkingMode, thinking: Boolean, tokens: Int?, onToggle: () -> Unit) {
    val open = mode != ThinkingMode.COLLAPSED
    val description = if (open) "Replier la réflexion" else "Afficher la réflexion"
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = description, onClick = onToggle)
            .heightIn(min = 48.dp)
            .padding(horizontal = 4.dp)
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Menu,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "Réflexion",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (thinking) {
            Spacer(Modifier.width(10.dp))
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        } else if (tokens != null && tokens > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                "· ${formatInt(tokens)} tokens",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun ThinkingText(
    messageId: String,
    text: String,
    style: MarkdownStyle,
    streaming: Boolean,
    preview: Boolean,
    fromEnd: Boolean,
) {
    MarkdownText(
        text = text,
        style = style,
        streaming = streaming,
        maxLines = if (preview) 5 else Int.MAX_VALUE,
        previewFromEnd = fromEnd,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        selectable = !streaming,
        resetKey = "r:$messageId",
        modifier = Modifier.padding(start = 12.dp, end = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun ShowAllButton(reasoning: String, onClick: () -> Unit) {
    if (ThinkingStates.mayOverflowPreview(reasoning)) {
        TextButton(onClick = onClick, modifier = Modifier.padding(start = 4.dp)) { Text("Tout afficher") }
    }
}

@Composable
private fun ShrinkButton(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.padding(start = 4.dp)) { Text("Réduire") }
}
