package app.fwchat.ui.shell

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fwchat.R
import app.fwchat.domain.ChatSummary

/** Contenu du tiroir (à placer dans un `ModalDrawerSheet`, qui gère les insets haut/bas). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DrawerContent(
    viewModel: DrawerViewModel,
    currentChatId: String?,
    onNewChat: () -> Unit,
    onOpenChat: (String) -> Unit,
    onCurrentChatDeleted: () -> Unit,
    onOpenPrompts: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val generating by viewModel.generating.collectAsStateWithLifecycle()

    var renaming by remember { mutableStateOf<ChatSummary?>(null) }
    var deleting by remember { mutableStateOf<ChatSummary?>(null) }
    val imeVisible = WindowInsets.isImeVisible

    Column(modifier = modifier.fillMaxSize().imePadding()) {
        Text(
            text = stringResource(R.string.drawer_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 28.dp, end = 16.dp, top = 20.dp, bottom = 12.dp),
        )
        Button(
            onClick = onNewChat,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.drawer_new_chat), modifier = Modifier.padding(start = 8.dp))
        }
        SearchField(
            query = query,
            onQueryChange = viewModel::setQuery,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.rows.isNotEmpty() -> ChatList(
                    rows = state.rows,
                    currentChatId = currentChatId,
                    generating = generating,
                    onOpenChat = onOpenChat,
                    onRename = { renaming = it },
                    onDuplicate = { viewModel.duplicate(it.id, onForked = onOpenChat) },
                    onDelete = { deleting = it },
                )
                state.loaded -> Text(
                    text = stringResource(
                        if (query.isBlank()) R.string.drawer_empty else R.string.drawer_no_results,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                )
            }
        }

        if (!imeVisible) {
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            FooterEntry(Icons.Outlined.Tune, stringResource(R.string.drawer_prompts), onOpenPrompts)
            FooterEntry(Icons.Outlined.Settings, stringResource(R.string.drawer_settings), onOpenSettings)
        }
    }

    renaming?.let { chat ->
        RenameDialog(
            initial = chat.title,
            onDismiss = { renaming = null },
            onConfirm = { viewModel.rename(chat.id, it); renaming = null },
        )
    }
    deleting?.let { chat ->
        val name = chat.title.ifBlank { stringResource(R.string.drawer_untitled_chat) }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_chat_title)) },
            text = { Text(stringResource(R.string.delete_chat_message, name)) },
            confirmButton = {
                TextButton(onClick = {
                    val id = chat.id
                    deleting = null
                    viewModel.delete(id) { if (id == currentChatId) onCurrentChatDeleted() }
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        placeholder = { Text(stringResource(R.string.drawer_search_hint)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.drawer_search_clear))
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors = TextFieldDefaults.colors(),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatList(
    rows: List<DrawerRow>,
    currentChatId: String?,
    generating: Set<String>,
    onOpenChat: (String) -> Unit,
    onRename: (ChatSummary) -> Unit,
    onDuplicate: (ChatSummary) -> Unit,
    onDelete: (ChatSummary) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
    ) {
        items(rows, key = { it.key }, contentType = { it::class }) { row ->
            when (row) {
                is DrawerRow.Header -> Text(
                    text = stringResource(bucketLabel(row.bucket)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                )
                is DrawerRow.Item -> ChatRow(
                    chat = row.chat,
                    selected = row.chat.id == currentChatId,
                    generating = row.chat.id in generating,
                    onClick = { onOpenChat(row.chat.id) },
                    onRename = { onRename(row.chat) },
                    onDuplicate = { onDuplicate(row.chat) },
                    onDelete = { onDelete(row.chat) },
                )
            }
        }
    }
}

private fun bucketLabel(b: DateBucket): Int = when (b) {
    DateBucket.TODAY -> R.string.bucket_today
    DateBucket.YESTERDAY -> R.string.bucket_yesterday
    DateBucket.LAST_7_DAYS -> R.string.bucket_7_days
    DateBucket.LAST_30_DAYS -> R.string.bucket_30_days
    DateBucket.OLDER -> R.string.bucket_older
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(
    chat: ChatSummary,
    selected: Boolean,
    generating: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val container = if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(container)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuOpen = true
                },
            ),
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            headlineContent = {
                Text(
                    text = chat.title.ifBlank { stringResource(R.string.drawer_untitled_chat) },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = {
                Text(
                    text = chat.modelId.substringAfterLast('/'),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (generating) {
                        val desc = stringResource(R.string.drawer_generating)
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp).semantics { contentDescription = desc },
                            strokeWidth = 2.dp,
                        )
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.action_more),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_rename)) },
                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                onClick = { menuOpen = false; onRename() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_duplicate)) },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                enabled = chat.messageCount > 0,
                onClick = { menuOpen = false; onDuplicate() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_delete)) },
                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                onClick = { menuOpen = false; onDelete() },
            )
        }
    }
}

@Composable
private fun FooterEntry(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(CircleShape)
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_chat_title)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(stringResource(R.string.rename_chat_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(enabled = value.isNotBlank(), onClick = { onConfirm(value) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
