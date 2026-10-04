package app.fwchat.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fwchat.domain.ModelInfo
import kotlinx.coroutines.launch

/**
 * Feuille du bas pour choisir un modèle: recherche, liste (nom court, contexte, badge « vision »),
 * modèle sélectionné coché, bouton ↻ qui appelle [onRefresh] (spinner + message d'erreur éventuel).
 * La feuille ne se ferme pas toute seule: l'appelant doit réagir à [onSelect] (en général fermer).
 *
 * @param selectedId id complet du modèle courant (peut être absent de [models]: affiché en tête, « indisponible »).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    models: List<ModelInfo>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onRefresh: suspend () -> Result<Unit>,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val filtered = remember(models, query) { filterModels(models, query) }
    val missingSelected = selectedId != null && models.none { it.id == selectedId }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Modèle", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (refreshing) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                } else {
                    IconButton(onClick = {
                        refreshing = true
                        error = null
                        scope.launch {
                            val r = onRefresh()
                            refreshing = false
                            error = r.exceptionOrNull()?.let { it.message ?: "Échec du rechargement des modèles." }
                        }
                    }) { Icon(Icons.Filled.Refresh, contentDescription = "Recharger la liste des modèles") }
                }
            }
            if (error != null) {
                Text(
                    error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Rechercher un modèle") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) {
                    { IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Effacer la recherche") } }
                } else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                if (missingSelected && query.isBlank()) {
                    item(key = "missing") {
                        ModelRow(
                            title = selectedId!!.substringAfterLast('/'),
                            subtitle = "Indisponible dans la liste actuelle",
                            vision = false,
                            selected = true,
                            onClick = { onSelect(selectedId) },
                        )
                    }
                }
                items(filtered, key = { it.id }) { m ->
                    ModelRow(
                        title = m.shortId,
                        subtitle = formatContext(m.contextLength)?.let { "Contexte $it" } ?: "Contexte inconnu",
                        vision = m.supportsImageInput,
                        selected = m.id == selectedId,
                        onClick = { onSelect(m.id) },
                    )
                }
                if (filtered.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            if (models.isEmpty()) "Aucun modèle. Appuyez sur ↻ pour charger la liste." else "Aucun résultat.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }
                item(key = "end") { Spacer(Modifier.size(16.dp)) }
            }
        }
    }
}

/** Filtre insensible à la casse sur l'id et le nom affiché. */
fun filterModels(models: List<ModelInfo>, query: String): List<ModelInfo> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return models
    return models.filter { it.shortId.lowercase().contains(q) || it.displayName.lowercase().contains(q) }
}

@Composable
private fun ModelRow(title: String, subtitle: String, vision: Boolean, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (vision) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    "vision",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = "Sélectionné", tint = MaterialTheme.colorScheme.primary)
        } else {
            Spacer(Modifier.size(24.dp))
        }
    }
}
