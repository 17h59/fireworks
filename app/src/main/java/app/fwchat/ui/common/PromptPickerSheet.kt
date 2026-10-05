package app.fwchat.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fwchat.domain.SystemPrompt

/**
 * Feuille du bas pour choisir le prompt système: « Aucun prompt système » puis la bibliothèque
 * (nom + aperçu d'une ligne). [selectedId] null = « Aucun ». [onSelect] reçoit null pour « Aucun ».
 *
 * @param defaultId prompt par défaut (réglages), marqué d'une étoile; null = aucune marque.
 * @param onManage si non null, dernière ligne « Gérer mes prompts… » (l'appelant ferme la feuille puis navigue).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptPickerSheet(
    prompts: List<SystemPrompt>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
    defaultId: String? = null,
    onManage: (() -> Unit)? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                "Prompt système",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                item(key = "none") {
                    PromptRow(
                        title = "Aucun prompt système",
                        preview = "Le modèle reçoit directement tes messages.",
                        selected = selectedId == null,
                        onClick = { onSelect(null) },
                    )
                }
                items(prompts, key = { it.id }) { p ->
                    PromptRow(
                        title = p.name,
                        preview = p.text.trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
                        selected = p.id == selectedId,
                        isDefault = defaultId != null && p.id == defaultId,
                        onClick = { onSelect(p.id) },
                    )
                }
                if (prompts.isEmpty()) {
                    item(key = "hint") {
                        Text(
                            "Ta bibliothèque de prompts est vide.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }
                if (onManage != null) {
                    item(key = "manage") { ManageRow(onManage) }
                }
                item(key = "end") { Spacer(Modifier.size(16.dp)) }
            }
        }
    }
}

@Composable
private fun ManageRow(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text("Gérer mes prompts…", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun PromptRow(
    title: String,
    preview: String,
    selected: Boolean,
    onClick: () -> Unit,
    isDefault: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isDefault) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = "Prompt par défaut",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(start = 6.dp).size(16.dp),
                    )
                }
            }
            if (preview.isNotEmpty()) {
                Text(
                    preview,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = "Sélectionné", tint = MaterialTheme.colorScheme.primary)
        } else {
            Spacer(Modifier.size(24.dp))
        }
    }
}
