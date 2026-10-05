package app.fwchat.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.fwchat.domain.Message
import app.fwchat.domain.MessageStatus
import app.fwchat.domain.StreamingText
import app.fwchat.domain.ThreadItem
import app.fwchat.ui.common.formatInt
import app.fwchat.ui.markdown.MarkdownBlocksLoader
import app.fwchat.ui.markdown.MarkdownStyle
import app.fwchat.ui.markdown.MarkdownTail
import app.fwchat.ui.markdown.MarkdownText

/** Limite la largeur à [fraction] de la largeur disponible (bulle utilisateur ≈ 85 %). */
private fun Modifier.maxWidthFraction(fraction: Float): Modifier = layout { measurable, constraints ->
    val limit = (constraints.maxWidth * fraction).toInt().coerceAtLeast(constraints.minWidth)
    val placeable = measurable.measure(constraints.copy(maxWidth = limit))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

// ------------------------------------------------------------------------------------------------
// Message utilisateur

private val USER_BUBBLE_SHAPE = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)

/** Message utilisateur court (au plus [LongText.THRESHOLD_CHARS] caractères): rendu markdown dans une bulle. */
@Composable
internal fun UserBubble(text: String, style: MarkdownStyle) {
    BubbleSurface(USER_BUBBLE_SHAPE, top = 12.dp, bottom = 2.dp, speaker = "Toi") {
        MarkdownText(text = text, style = style, selectable = true)
    }
}

@Composable
private fun BubbleSurface(
    shape: Shape,
    top: Dp,
    bottom: Dp,
    /** Annonce « Toi » pour les lecteurs d'écran (premier élément de la bulle seulement). */
    speaker: String? = null,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = top, bottom = bottom),
        contentAlignment = Alignment.CenterEnd,
    ) {
        if (speaker != null) SpeakerMarker(speaker, Modifier.align(Alignment.TopStart))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = shape,
            modifier = Modifier.maxWidthFraction(0.85f),
        ) {
            Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) { content() }
        }
    }
}

/**
 * Message utilisateur très long, replié: début du texte (≈ 12 lignes) + « Tout afficher ». Rien de lourd n'est
 * composé (un coller de 300 Ko ne fait ni layout géant ni ANR).
 */
@Composable
internal fun LongUserPreview(text: String, style: MarkdownStyle, onExpand: () -> Unit) {
    val preview = remember(text) { LongText.preview(text) + "…" }
    BubbleSurface(USER_BUBBLE_SHAPE, top = 12.dp, bottom = 2.dp, speaker = "Toi") {
        Column {
            Text(
                text = preview,
                style = style.body,
                maxLines = LongText.PREVIEW_LINES + 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onExpand) { Text("Tout afficher (${formatInt(text.length)} caractères)") }
        }
    }
}

/** Une tranche du texte complet d'un message utilisateur long déplié (une bulle découpée en items paresseux). */
@Composable
internal fun LongUserChunk(text: String, first: Boolean, last: Boolean, style: MarkdownStyle, onCollapse: () -> Unit) {
    val shape = RoundedCornerShape(
        topStart = if (first) 20.dp else 0.dp,
        topEnd = if (first) 20.dp else 0.dp,
        bottomEnd = if (last) 6.dp else 0.dp,
        bottomStart = if (last) 20.dp else 0.dp,
    )
    BubbleSurface(
        shape, top = if (first) 12.dp else 0.dp, bottom = if (last) 2.dp else 0.dp,
        speaker = if (first) "Toi" else null,
    ) {
        Column(Modifier.fillMaxWidth()) {
            SelectionContainer { Text(text, style = style.body, modifier = Modifier.fillMaxWidth()) }
            if (last) TextButton(onClick = onCollapse) { Text("Réduire") }
        }
    }
}


// ------------------------------------------------------------------------------------------------
// Message assistant: corps en direct et pied

/**
 * Repère pour les lecteurs d'écran: annonce qui parle (« Toi » / « Assistant ») sans doubler la lecture du texte
 * (le texte garde sa propre sémantique). 1 dp: n'a aucun effet visible sur la mise en page.
 */
@Composable
internal fun SpeakerMarker(who: String, modifier: Modifier = Modifier) {
    Box(
        modifier.size(1.dp).semantics {
            heading()
            contentDescription = who
        },
    )
}

/**
 * Partie vivante du message en cours: SEUL le dernier bloc (ouvert) est rendu ici — et, pour un gros bloc, sa
 * dernière tranche seulement. Les blocs fermés sont des items à part (voir `assistantItems`). Le texte live est lu
 * DANS cet item: un token ne recompose que lui.
 */
@Composable
internal fun LiveBody(entry: LiveEntry, streaming: State<Map<String, StreamingText>>, style: MarkdownStyle) {
    val live by rememberLiveText(entry.id, streaming)
    val content = live?.content ?: entry.fallback
    val parts = entry.markdown.parts(content)
    val block = parts.liveBlock
    when {
        // Mêmes marges que le rendu final (haut 0, bas = espacement de bloc): pas de saut à la fin du flux.
        block != null -> Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = style.blockSpacing)) {
            MarkdownTail(block = block, fromSlice = parts.liveFromSlice, style = style)
        }
        (live?.reasoning).isNullOrEmpty() -> Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
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
        when {
            m.status == MessageStatus.ERROR -> ErrorBlock(m, generating, cb)
            m.status == MessageStatus.INTERRUPTED -> Text(
                "Interrompu",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            m.isTruncated() -> TruncatedNotice(m, generating, cb)
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

/** Réponse coupée par la limite de tokens (finish_reason = length) alors qu'elle a un contenu partiel. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TruncatedNotice(m: Message, generating: Boolean, cb: MessageCallbacks) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.padding(top = 2.dp).size(20.dp),
                )
                Text(
                    "Réponse tronquée (limite de tokens atteinte)",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                )
            }
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(0.dp, Alignment.End),
            ) {
                TextButton(onClick = cb.onOpenParams) { Text("Paramètres") }
                TextButton(onClick = { cb.onRegenerate(m.id) }, enabled = !generating) { Text("Réessayer") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ErrorBlock(m: Message, generating: Boolean, cb: MessageCallbacks) {
    val kind = ErrorKinds.classify(m.error)
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.padding(top = 2.dp).size(20.dp),
                )
                Text(
                    m.error ?: "Une erreur est survenue.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                )
            }
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(0.dp, Alignment.End),
            ) {
                when (kind) {
                    ErrorKind.INVALID_KEY -> TextButton(onClick = cb.onOpenSettings) { Text("Réglages") }
                    ErrorKind.MODEL_NOT_FOUND -> TextButton(onClick = cb.onOpenModels) { Text("Choisir un modèle") }
                    ErrorKind.CUT_DURING_REASONING -> TextButton(onClick = cb.onOpenParams) { Text("Paramètres") }
                    ErrorKind.OTHER -> TextButton(onClick = { cb.onRetry(m.id) }, enabled = !generating) {
                        Text("Réessayer")
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Barre d'actions

/**
 * Sous un message: [chevrons de versions] puis Copier, Modifier, (assistant) Régénérer et UN menu ⋮ plat
 * (« Nouveau chat à partir d'ici », « Supprimer le message »). Toutes les zones tactiles font 48 dp; les deux
 * groupes passent à la ligne (FlowRow) si la largeur ou la taille de police l'exige.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActionsBar(ti: ThreadItem, assistant: Boolean, generating: Boolean, cb: MessageCallbacks) {
    val m = ti.message
    Column(
        Modifier.fillMaxWidth().padding(horizontal = if (assistant) 8.dp else 12.dp),
        horizontalAlignment = if (assistant) Alignment.Start else Alignment.End,
    ) {
        if (!assistant && m.edited) {
            Text(
                "modifié",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, if (assistant) Alignment.Start else Alignment.End),
            verticalArrangement = Arrangement.Center,
        ) {
            if (ti.siblingCount > 1) {
                val index = ti.siblingIndex
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ActionIcon(
                        Icons.Filled.ChevronLeft, "Version précédente",
                        enabled = !generating && index > 0,
                    ) { cb.onSibling(ti, -1) }
                    Text(
                        "${index + 1}/${ti.siblingCount}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { contentDescription = "Version ${index + 1} sur ${ti.siblingCount}" },
                    )
                    ActionIcon(
                        Icons.Filled.ChevronRight, "Version suivante",
                        enabled = !generating && index < ti.siblingCount - 1,
                    ) { cb.onSibling(ti, +1) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ActionIcon(Icons.Outlined.ContentCopy, "Copier le message") { cb.onCopy(m.content) }
                ActionIcon(Icons.Outlined.Edit, "Modifier le message", enabled = !generating) { cb.onStartEdit(m.id) }
                if (assistant) {
                    ActionIcon(Icons.Outlined.Refresh, "Régénérer la réponse", enabled = !generating) {
                        cb.onRegenerate(m.id)
                    }
                }
                MoreMenu(m.id, generating, cb)
            }
        }
    }
}

/** Menu ⋮ plat: deux entrées, pas de sous-menu. */
@Composable
private fun MoreMenu(messageId: String, generating: Boolean, cb: MessageCallbacks) {
    var open by remember { mutableStateOf(false) }
    Box {
        ActionIcon(Icons.Filled.MoreVert, "Plus d'actions sur le message") { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Nouveau chat à partir d'ici") },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.CallSplit, contentDescription = null) },
                onClick = {
                    open = false
                    cb.onFork(messageId)
                },
            )
            DropdownMenuItem(
                text = { Text("Supprimer le message") },
                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                enabled = !generating,
                onClick = {
                    open = false
                    cb.onDelete(messageId)
                },
            )
        }
    }
}

@Composable
private fun ActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
    }
}

// ------------------------------------------------------------------------------------------------
// Édition dans la liste

/**
 * Édition d'un message. Disposition adaptative (police jusqu'à 2.0, 360 dp): le bouton principal d'un message
 * utilisateur (« Envoyer et régénérer ») occupe toute la largeur; « Enregistrer » (sans renvoi) et « Annuler »
 * passent à la ligne si besoin.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditBox(message: Message, isUser: Boolean, generating: Boolean, cb: MessageCallbacks) {
    // Jamais de gros texte dans le Bundle (TransactionTooLargeException): au-delà de 50 000 caractères, non sauvegardé.
    var text by rememberSaveable(message.id, stateSaver = BoundedTextSaver) { mutableStateOf(message.content) }
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
        if (isUser) {
            Button(
                onClick = { cb.onSendEdit(message.id, text) },
                enabled = text.isNotBlank() && !generating,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text("Envoyer et régénérer", style = MaterialTheme.typography.titleMedium) }
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = cb.onCancelEdit, modifier = Modifier.heightIn(min = 48.dp)) { Text("Annuler") }
                OutlinedButton(
                    onClick = { cb.onSaveEdit(message.id, text) },
                    enabled = text.isNotBlank(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Enregistrer", style = MaterialTheme.typography.labelMedium) }
            }
        } else {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = cb.onCancelEdit, modifier = Modifier.heightIn(min = 48.dp)) { Text("Annuler") }
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

/**
 * Réflexion d'un message en cours: texte lu DANS cet item. Invisible tant qu'aucune réflexion n'est arrivée.
 * Le mode dépend du contenu de la réponse (lu ici): aperçu tant qu'elle n'a pas commencé, replié ensuite,
 * sauf choix explicite de l'utilisateur ([override]).
 */
@Composable
internal fun LiveThinking(
    m: Message,
    streaming: State<Map<String, StreamingText>>,
    override: ThinkingMode?,
    cb: MessageCallbacks,
    thinkStyle: MarkdownStyle,
) {
    val live by rememberLiveText(m.id, streaming)
    val reasoning = live?.reasoning ?: m.reasoning.orEmpty()
    if (reasoning.isEmpty()) return
    val content = live?.content ?: m.content
    val thinking = ThinkingStates.isThinking(MessageStatus.STREAMING, content)
    val mode = ThinkingStates.resolve(override, messageStreaming = true, hasContent = content.isNotEmpty())
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp)) {
        ThinkingHeader(mode, thinking, tokens = null) { cb.onThinkingToggle(m.id, mode) }
        when (mode) {
            ThinkingMode.COLLAPSED -> Unit
            ThinkingMode.PREVIEW -> {
                // Les dernières lignes tant que le modèle réfléchit (on suit le flux); sinon le début, comme le rendu final.
                ThinkingText(m.id, reasoning, thinkStyle, streaming = thinking, preview = true, fromEnd = thinking)
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
    loader: MarkdownBlocksLoader,
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
        markdownBody(
            key = "m:${m.id}:r",
            cacheKey = "r:${m.id}",
            text = reasoning,
            style = thinkStyle,
            modifier = Modifier.padding(start = 28.dp, end = 16.dp),
            loader = loader,
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
    val chevronRotation by animateFloatAsState(if (open) 180f else 0f, label = "thinking-chevron")
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
        // Chevron: ouvert (vers le haut) / fermé (vers le bas).
        Icon(
            Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp).size(18.dp).rotate(chevronRotation),
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
