package app.fwchat.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.fwchat.AppContainer
import app.fwchat.ui.common.GenParamsSheet
import app.fwchat.ui.common.ModelPickerSheet
import app.fwchat.ui.common.PromptPickerSheet
import kotlinx.coroutines.launch

/**
 * Écran de chat. [chatId] null = nouveau chat (brouillon en mémoire, créé en base au 1er envoi).
 * [onChatCreated] est appelé au 1er envoi d'un brouillon et après un fork: la coque navigue vers `chat/{id}`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    container: AppContainer,
    chatId: String?,
    onOpenDrawer: () -> Unit,
    onChatCreated: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val factory = remember(container, chatId) {
        viewModelFactory {
            initializer {
                ChatViewModel(
                    chats = container.chats,
                    prompts = container.prompts,
                    settings = container.settings,
                    models = container.models,
                    engine = container.engine,
                    chatId = chatId,
                    appScope = container.appScope,
                )
            }
        }
    }
    val vm: ChatViewModel = viewModel(key = chatId ?: "chat-draft", factory = factory)
    val ui by vm.state.collectAsStateWithLifecycle()
    // On passe le State lui-même (sans le lire ici): seuls les items du message en cours le lisent.
    val streaming = vm.streaming.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val following = rememberSaveable { mutableStateOf(true) }

    var draftText by rememberSaveable { mutableStateOf("") }
    var showModels by rememberSaveable { mutableStateOf(false) }
    var showPrompts by rememberSaveable { mutableStateOf(false) }
    var showParams by rememberSaveable { mutableStateOf(false) }
    var showPromptText by rememberSaveable { mutableStateOf(false) }
    var showRename by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var deleteTarget by rememberSaveable { mutableStateOf<String?>(null) }

    fun toast(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    fun copy(text: String, confirmation: String) {
        toast(if (copyToClipboard(context, text)) confirmation else "Copie impossible (texte trop volumineux ?)")
    }

    val currentOnChatCreated by rememberUpdatedState(onChatCreated)
    val currentOnOpenSettings by rememberUpdatedState(onOpenSettings)

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is ChatUiEvent.OpenChat -> currentOnChatCreated(event.chatId)
                is ChatUiEvent.CopyText -> copy(event.text, event.confirmation)
                is ChatUiEvent.Notice -> toast(event.message)
                ChatUiEvent.ScrollToBottom -> {
                    following.value = true
                    listState.scrollToBottom()
                }
            }
        }
    }

    LaunchedEffect(vm) {
        vm.engineNotices.collect { notice ->
            scope.launch {
                when (notice) {
                    EngineNotice.Unauthorized -> {
                        val result = snackbar.showSnackbar(
                            message = "Clé API invalide",
                            actionLabel = "Réglages",
                            duration = SnackbarDuration.Long,
                        )
                        if (result == SnackbarResult.ActionPerformed) currentOnOpenSettings()
                    }
                    is EngineNotice.Message -> snackbar.showSnackbar(notice.text)
                }
            }
        }
    }

    val callbacks = remember(vm) {
        MessageCallbacks(
            onCopy = { text -> copy(text, "Copié") },
            onStartEdit = { id ->
                following.value = false
                vm.startEdit(id)
            },
            onCancelEdit = vm::cancelEdit,
            onSaveEdit = vm::saveEdit,
            onSendEdit = { id, text ->
                following.value = true
                vm.sendEdit(id, text)
            },
            onRegenerate = { id ->
                following.value = true
                vm.regenerate(id)
            },
            onFork = vm::fork,
            onDelete = { id -> deleteTarget = id },
            onSibling = vm::selectSibling,
            onThinkingToggle = vm::toggleThinking,
            onThinkingShowAll = vm::showAllThinking,
            onThinkingShrink = vm::shrinkThinking,
        )
    }

    val showDownButton by remember { derivedStateOf { !following.value && listState.canScrollForward } }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        // Les insets du bas (clavier, barre de navigation) sont gérés par le composer lui-même.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = ui.title.ifBlank { "Nouveau chat" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (ui.isDraft) Modifier else Modifier.clickable(
                            onClickLabel = "Renommer le chat",
                        ) { showRename = true },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Filled.Menu, contentDescription = "Ouvrir le menu")
                    }
                },
                actions = {
                    IconButton(onClick = { showParams = true }) {
                        Icon(Icons.Outlined.Tune, contentDescription = "Paramètres de génération")
                    }
                    if (!ui.isDraft) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Plus d'actions")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Renommer") },
                                    onClick = {
                                        menuOpen = false
                                        showRename = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Copier toute la conversation") },
                                    onClick = {
                                        menuOpen = false
                                        vm.copyConversation()
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ChatPills(
                ui = ui,
                onModel = { showModels = true },
                onPrompt = { if (ui.promptLocked) showPromptText = true else showPrompts = true },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (ui.notFound) {
                    Text(
                        "Cette conversation n'existe plus.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    )
                } else {
                    MessageList(
                        ui = ui,
                        streaming = streaming,
                        callbacks = callbacks,
                        listState = listState,
                        following = following,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (ui.thread.isEmpty() && ui.loaded) {
                        EmptyState(ui, Modifier.align(Alignment.Center))
                    }
                }
                ScrollDownButton(
                    visible = showDownButton,
                    onClick = {
                        following.value = true
                        scope.launch { listState.scrollToBottom() }
                    },
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
                SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
            }
            Composer(
                text = draftText,
                onTextChange = { draftText = it },
                generating = ui.generating,
                sendEnabled = draftText.isNotBlank() && !ui.generating && (if (ui.isDraft) ui.loaded else ui.canSend),
                onSend = {
                    if (vm.send(draftText)) {
                        draftText = ""
                        following.value = true
                    }
                },
                onStop = vm::stop,
            )
        }
    }

    // ------------------------------------------------------------------ feuilles du bas

    if (showModels) {
        ModelPickerSheet(
            models = ui.models,
            selectedId = ui.modelId,
            onSelect = { id ->
                vm.setModel(id)
                showModels = false
            },
            onRefresh = vm::refreshModels,
            onDismiss = { showModels = false },
        )
    }
    if (showPrompts && ui.isDraft) {
        PromptPickerSheet(
            prompts = ui.prompts,
            selectedId = ui.selectedPromptId,
            onSelect = { id ->
                vm.setPrompt(id)
                showPrompts = false
            },
            onDismiss = { showPrompts = false },
        )
    }
    if (showParams) {
        GenParamsSheet(
            params = ui.params,
            onChange = vm::setParams,
            modelId = ui.modelId,
            onDismiss = {
                vm.flushParams()
                showParams = false
            },
        )
    }

    // ------------------------------------------------------------------ dialogues

    if (showPromptText) {
        SystemPromptDialog(
            name = ui.promptName,
            text = ui.promptText,
            onCopy = { text -> copy(text, "Prompt copié") },
            onDismiss = { showPromptText = false },
        )
    }
    if (showRename && !ui.isDraft) {
        RenameDialog(
            initial = ui.title,
            onConfirm = { title ->
                vm.rename(title)
                showRename = false
            },
            onDismiss = { showRename = false },
        )
    }
    deleteTarget?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Supprimer ce message ?") },
            text = {
                Text(
                    "Ce message ET toute la suite de la conversation qui en dépend " +
                        "(ses réponses et leurs variantes) seront supprimés. Cette action est irréversible.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    vm.deleteMessage(id)
                }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Annuler") } },
        )
    }
}

// ------------------------------------------------------------------------------------------------

private fun copyToClipboard(context: Context, text: String): Boolean = try {
    val manager = context.getSystemService(ClipboardManager::class.java)
    manager.setPrimaryClip(ClipData.newPlainText("FW Chat", text))
    true
} catch (e: Exception) {
    false
}

/** Pastilles [Modèle] et [Prompt système] sous la barre du haut. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatPills(ui: ChatUiState, onModel: () -> Unit, onPrompt: () -> Unit) {
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    val colors = AssistChipDefaults.assistChipColors(containerColor = container)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val modelLabel = when {
            ui.modelId == null -> "Choisir un modèle"
            !ui.modelAvailable -> "${ui.modelId.substringAfterLast('/')} · indisponible"
            else -> ui.modelId.substringAfterLast('/')
        }
        AssistChip(
            onClick = onModel,
            label = { Text(modelLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Outlined.Memory, contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = { Icon(Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp)) },
            colors = colors,
            border = null,
            modifier = Modifier.weight(1f, fill = false).semantics {
                contentDescription = "Modèle : $modelLabel. Appuyer pour changer."
            },
        )
        val promptLabel = ui.promptName ?: if (ui.promptLocked) "Sans prompt système" else "Aucun prompt système"
        AssistChip(
            onClick = onPrompt,
            label = { Text(promptLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = {
                Icon(
                    if (ui.promptLocked) Icons.Filled.Lock else Icons.Outlined.Description,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            trailingIcon = if (ui.promptLocked) null else {
                { Icon(Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp)) }
            },
            colors = colors,
            border = null,
            modifier = Modifier.weight(1f, fill = false).semantics {
                contentDescription = if (ui.promptLocked) {
                    "Prompt système : $promptLabel, verrouillé. Appuyer pour le lire."
                } else {
                    "Prompt système : $promptLabel. Appuyer pour changer."
                }
            },
        )
    }
}

@Composable
private fun EmptyState(ui: ChatUiState, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp),
        )
        Text(
            if (ui.isDraft) "Que puis-je faire pour vous ?" else "Cette conversation est vide.",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        if (ui.isDraft) {
            Text(
                if (ui.models.isEmpty()) {
                    "Aucun modèle chargé : ouvrez la pastille du modèle pour recharger la liste."
                } else {
                    "Choisissez le modèle et le prompt système ci-dessus avant d'écrire : le prompt sera figé au premier message."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SystemPromptDialog(name: String?, text: String?, onCopy: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (text == null) "Sans prompt système" else name ?: "Prompt système") },
        text = {
            if (text == null) {
                Text("Ce chat a été créé sans prompt système.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Figé à la création du chat : il ne peut plus être modifié.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SelectionContainer {
                        Text(
                            text,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
        dismissButton = if (text != null) {
            { TextButton(onClick = { onCopy(text) }) { Text("Copier") } }
        } else null,
    )
}

@Composable
private fun RenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Renommer le chat") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                placeholder = { Text("Nouveau chat") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text("Renommer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** Bouton discret « ↓ », visible seulement quand on n'est pas en bas. (Fonction à part: évite le conflit d'AnimatedVisibility avec ColumnScope.) */
@Composable
private fun ScrollDownButton(visible: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier.padding(end = 16.dp, bottom = 12.dp),
    ) {
        SmallFloatingActionButton(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Aller en bas de la conversation")
        }
    }
}
