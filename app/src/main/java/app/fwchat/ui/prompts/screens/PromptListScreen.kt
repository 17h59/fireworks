package app.fwchat.ui.prompts.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fwchat.R
import app.fwchat.domain.SystemPrompt
import app.fwchat.ui.prompts.PromptTemplates

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptListScreen(
    onBack: () -> Unit,
    onOpenPrompt: (String) -> Unit,
    onNewPrompt: () -> Unit,
    onNewPromptFromTemplate: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PromptListViewModel = viewModel(factory = PromptListViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<SystemPrompt?>(null) }
    var pickingTemplate by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.prompts_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = onNewPrompt) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.prompts_add))
                    }
                },
            )
        },
    ) { padding ->
        val dir = LocalLayoutDirection.current
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp + padding.calculateLeftPadding(dir),
                end = 16.dp + padding.calculateRightPadding(dir),
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "none") {
                PromptCard(
                    title = stringResource(R.string.prompts_none_name),
                    family = null,
                    preview = stringResource(R.string.prompts_none_desc),
                    isDefault = state.defaultId == null,
                    onToggleDefault = viewModel::setNoneAsDefault,
                    onClick = null,
                    onDelete = null,
                )
            }
            if (state.loaded && state.prompts.isEmpty()) {
                item(key = "empty") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            stringResource(R.string.prompts_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.prompts_empty_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = { pickingTemplate = true },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.prompts_from_template)) }
                    }
                }
            }
            items(state.prompts, key = { it.id }) { prompt ->
                PromptCard(
                    title = prompt.name,
                    family = prompt.family?.label,
                    preview = prompt.text,
                    isDefault = state.defaultId == prompt.id,
                    onToggleDefault = { viewModel.toggleDefault(prompt.id) },
                    onClick = { onOpenPrompt(prompt.id) },
                    onDelete = { deleting = prompt },
                )
            }
        }
    }

    deleting?.let { prompt ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.prompts_delete_title)) },
            text = { Text(stringResource(R.string.prompts_delete_message, prompt.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(prompt.id)
                    deleting = null
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (pickingTemplate) {
        AlertDialog(
            onDismissRequest = { pickingTemplate = false },
            title = { Text(stringResource(R.string.prompts_pick_template)) },
            text = {
                LazyColumn {
                    items(PromptTemplates.all, key = { it.id }) { t ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clickable {
                                    pickingTemplate = false
                                    onNewPromptFromTemplate(t.id)
                                }
                                .padding(vertical = 8.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(t.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                t.family?.label ?: stringResource(R.string.editor_family_other),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pickingTemplate = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun PromptCard(
    title: String,
    family: String?,
    preview: String,
    isDefault: Boolean,
    onToggleDefault: () -> Unit,
    onClick: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    val colors = CardDefaults.cardColors(
        containerColor = if (isDefault) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
    )
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (family != null) {
                    Text(
                        family,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            IconButton(onClick = onToggleDefault) {
                Icon(
                    imageVector = if (isDefault) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = stringResource(
                        if (isDefault) R.string.prompts_default_set else R.string.prompts_default_unset,
                    ),
                    tint = if (isDefault) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (onClick != null) {
        Card(onClick = onClick, colors = colors, modifier = Modifier.fillMaxWidth()) { content() }
    } else {
        Card(colors = colors, modifier = Modifier.fillMaxWidth()) { content() }
    }
}
