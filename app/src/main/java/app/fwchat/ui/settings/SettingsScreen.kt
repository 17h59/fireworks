package app.fwchat.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fwchat.AppContainer
import app.fwchat.BuildConfig
import app.fwchat.ui.common.GenParamsEditor
import app.fwchat.ui.common.ModelPickerSheet
import app.fwchat.ui.common.PromptPickerSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Écran de réglages: une seule page défilante, découpée en sections (clé API, modèle, prompt système,
 * paramètres de génération, sauvegarde, à propos). On n'en sort que vers la bibliothèque de prompts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenPrompts: () -> Unit,
    onApiKeyRemoved: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    var editingKey by rememberSaveable { mutableStateOf(false) }
    var newKey by rememberSaveable { mutableStateOf("") }
    var keyVisible by rememberSaveable { mutableStateOf(false) }
    var confirmRemoveKey by rememberSaveable { mutableStateOf(false) }
    var showModels by rememberSaveable { mutableStateOf(false) }
    var showPrompts by rememberSaveable { mutableStateOf(false) }
    var importUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var confirmReplaceAll by rememberSaveable { mutableStateOf(false) }

    val currentOnApiKeyRemoved by rememberUpdatedState(onApiKeyRemoved)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsEvent.Message -> {
                    snackbar.currentSnackbarData?.dismiss()
                    launch { snackbar.showSnackbar(event.text) }
                }
                SettingsEvent.KeyReplaced -> {
                    editingKey = false
                    newKey = ""
                    keyVisible = false
                }
                SettingsEvent.ApiKeyRemoved -> currentOnApiKeyRemoved()
            }
        }
    }
    // Une modification de paramètres encore en attente de debounce est enregistrée en quittant l'écran.
    DisposableEffect(viewModel) { onDispose { viewModel.flushParams() } }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            viewModel.export { json ->
                withContext(Dispatchers.IO) {
                    val out = context.contentResolver.openOutputStream(uri, "wt")
                        ?: error("Impossible d'ouvrir le fichier de destination")
                    out.bufferedWriter(Charsets.UTF_8).use { it.write(json) }
                }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importUri = uri
    }

    fun runImport(uri: Uri, replace: Boolean) {
        viewModel.import(
            read = {
                withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: error("Impossible d'ouvrir le fichier choisi")
                    input.bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
            },
            replace = replace,
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Réglages") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            // ------------------------------------------------------------ clé API
            Section("Clé API Fireworks") {
                if (state.hasApiKey) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Clé enregistrée", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                state.keyHint ?: "••••",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    Text(
                        "Aucune clé enregistrée.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                if (editingKey) {
                    OutlinedTextField(
                        value = newKey,
                        onValueChange = { newKey = it; viewModel.clearKeyError() },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        label = { Text("Nouvelle clé API") },
                        singleLine = true,
                        enabled = !state.validatingKey,
                        isError = state.keyError != null,
                        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { viewModel.replaceKey(newKey) }),
                        trailingIcon = {
                            IconButton(onClick = { keyVisible = !keyVisible }) {
                                Icon(
                                    imageVector = if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (keyVisible) "Masquer la clé" else "Afficher la clé",
                                )
                            }
                        },
                        supportingText = state.keyError?.let { err -> { Text(err) } },
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            enabled = !state.validatingKey,
                            modifier = Modifier.heightIn(min = 48.dp),
                            onClick = {
                                scope.launch {
                                    val text = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }
                                        ?.getItemAt(0)?.text?.toString()
                                    if (!text.isNullOrBlank()) {
                                        newKey = text.trim()
                                        viewModel.clearKeyError()
                                    }
                                }
                            },
                        ) { Text("Coller") }
                        TextButton(
                            enabled = !state.validatingKey,
                            modifier = Modifier.heightIn(min = 48.dp),
                            onClick = {
                                editingKey = false
                                newKey = ""
                                keyVisible = false
                                viewModel.clearKeyError()
                            },
                        ) { Text("Annuler") }
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = { viewModel.replaceKey(newKey) },
                            enabled = !state.validatingKey && newKey.isNotBlank(),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            if (state.validatingKey) {
                                CircularProgressIndicator(
                                    Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            } else {
                                Text("Valider")
                            }
                        }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        OutlinedButton(
                            onClick = { editingKey = true },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(if (state.hasApiKey) "Remplacer la clé" else "Ajouter une clé") }
                        if (state.hasApiKey) {
                            TextButton(
                                onClick = { confirmRemoveKey = true },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text("Supprimer la clé", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }

            SectionDivider()

            // ------------------------------------------------------------ modèle par défaut
            Section("Modèle par défaut") {
                ClickableRow(
                    title = modelSummary(state.defaultModelId, state.models),
                    subtitle = "Utilisé pour les nouveaux chats",
                    onClick = { showModels = true },
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = viewModel::refreshModels,
                        enabled = !state.refreshingModels,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        if (state.refreshingModels) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("  Rechargement…")
                        } else {
                            Text("Recharger la liste des modèles")
                        }
                    }
                }
                Text(
                    modelCountLabel(state.models.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            SectionDivider()

            // ------------------------------------------------------------ prompt système par défaut
            Section("Prompt système par défaut") {
                ClickableRow(
                    title = promptLabel(state.defaultPromptId, state.prompts),
                    subtitle = "Copié dans chaque nouveau chat",
                    onClick = { showPrompts = true },
                )
                TextButton(
                    onClick = onOpenPrompts,
                    modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp),
                ) { Text("Gérer mes prompts système") }
            }

            SectionDivider()

            // ------------------------------------------------------------ paramètres de génération
            Section("Paramètres de génération par défaut") {
                Text(
                    "Appliqués aux nouveaux chats. Chaque chat garde ses propres réglages.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                val params = state.params
                if (params != null) {
                    GenParamsEditor(
                        params = params,
                        onChange = viewModel::onParamsChange,
                        modelId = state.defaultModelId,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            SectionDivider()

            // ------------------------------------------------------------ sauvegarde
            Section("Sauvegarde") {
                Text(
                    "Tout est stocké uniquement sur ton téléphone ; la sauvegarde n'inclut pas ta clé API.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                val busy = state.backupOp != null
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
                    Text(
                        if (state.backupOp == BackupOp.EXPORTING) "Export en cours…" else "Import en cours…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OutlinedButton(
                        enabled = !busy,
                        modifier = Modifier.heightIn(min = 48.dp),
                        onClick = { exportLauncher.launch(backupFileName(LocalDate.now())) },
                    ) { Text("Exporter mes données") }
                    OutlinedButton(
                        enabled = !busy,
                        modifier = Modifier.heightIn(min = 48.dp),
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                    ) { Text("Importer une sauvegarde") }
                }
            }

            SectionDivider()

            // ------------------------------------------------------------ à propos
            Section("À propos") {
                Text(
                    "FW Chat, version ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Text(
                    "Application locale pour Fireworks AI. Aucune donnée ne quitte ton téléphone hormis les requêtes d'inférence.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }

    // ------------------------------------------------------------------ feuilles et dialogues

    if (showModels) {
        ModelPickerSheet(
            models = state.models,
            selectedId = state.defaultModelId,
            onSelect = { id ->
                viewModel.setDefaultModel(id)
                showModels = false
            },
            onRefresh = viewModel::refreshModelsForSheet,
            onDismiss = { showModels = false },
        )
    }
    if (showPrompts) {
        PromptPickerSheet(
            prompts = state.prompts,
            selectedId = state.defaultPromptId,
            onSelect = { id ->
                viewModel.setDefaultPrompt(id)
                showPrompts = false
            },
            onDismiss = { showPrompts = false },
        )
    }
    if (confirmRemoveKey) {
        AlertDialog(
            onDismissRequest = { confirmRemoveKey = false },
            title = { Text("Supprimer la clé ?") },
            text = {
                Text(
                    "La clé sera effacée de ce téléphone. Tes conversations restent intactes, " +
                        "mais il faudra saisir une clé pour continuer à discuter.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemoveKey = false
                        viewModel.removeKey()
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveKey = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Annuler")
                }
            },
        )
    }
    val pendingImport = importUri
    if (pendingImport != null && !confirmReplaceAll) {
        AlertDialog(
            onDismissRequest = { importUri = null },
            title = { Text("Importer une sauvegarde") },
            text = {
                Text(
                    "Fusionner ajoute les chats et prompts du fichier à ceux de ton téléphone " +
                        "(ceux qui existent déjà sont conservés tels quels). " +
                        "Remplacer tout efface d'abord toutes tes données actuelles.",
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(
                        onClick = {
                            importUri = null
                            runImport(pendingImport, replace = false)
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Fusionner") }
                    TextButton(
                        onClick = { confirmReplaceAll = true },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Remplacer tout", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { importUri = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Annuler")
                    }
                }
            },
        )
    }
    if (pendingImport != null && confirmReplaceAll) {
        AlertDialog(
            onDismissRequest = { confirmReplaceAll = false; importUri = null },
            title = { Text("Tout remplacer ?") },
            text = {
                Text(
                    "Tous tes chats et tes prompts système actuels seront supprimés définitivement " +
                        "et remplacés par le contenu du fichier. Si tu n'as pas de sauvegarde récente, " +
                        "exporte tes données d'abord.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReplaceAll = false
                        importUri = null
                        runImport(pendingImport, replace = true)
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Tout remplacer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmReplaceAll = false; importUri = null },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Annuler") }
            },
        )
    }
}

// ---------------------------------------------------------------------- composants locaux

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        content()
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun ClickableRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
