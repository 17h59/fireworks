package app.fwchat.ui.common

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fwchat.data.net.toUserMessage
import app.fwchat.domain.FireworksException
import app.fwchat.domain.ModelInfo
import app.fwchat.domain.PlanInfo
import app.fwchat.domain.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Délai avant de filtrer après une frappe. */
private const val SEARCH_DEBOUNCE_MS = 150L

/**
 * Feuille du bas pour choisir un modèle. En haut, le segment « Fireworks | Featherless » change de mode d'un geste
 * ([onSwitchProvider]); un fournisseur sans clé propose « Ajouter une clé » ([onAddKey]). La recherche filtre hors du
 * thread principal (debounce 150 ms) et la liste n'affiche jamais plus de [MODEL_LIST_LIMIT] lignes: Featherless en
 * compte ~7 500. La feuille ne se ferme pas toute seule: l'appelant doit réagir à [onSelect] (en général fermer).
 *
 * @param models modèles du fournisseur ACTIF ([activeProvider]), dans l'ordre du dépôt.
 * @param selectedId id complet du modèle courant (peut être absent de [models]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    models: List<ModelInfo>,
    selectedId: String?,
    activeProvider: Provider,
    providersWithKey: Set<Provider>,
    plan: PlanInfo?,
    onSelect: (String) -> Unit,
    onSwitchProvider: (Provider) -> Unit,
    onAddKey: (Provider) -> Unit,
    onRefresh: suspend () -> Result<Unit>,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    // Fournisseur sans clé touché dans le segment (nom de l'enum, sauvegardable).
    var pendingName by rememberSaveable { mutableStateOf<String?>(null) }
    val pending = pendingName?.let { n -> Provider.entries.firstOrNull { it.name == n } }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Filtrage hors du thread principal; la première page (requête vide) n'attend pas le debounce.
    var page by remember { mutableStateOf<ModelPage?>(null) }
    LaunchedEffect(models, query) {
        if (query.isNotBlank()) delay(SEARCH_DEBOUNCE_MS)
        page = withContext(Dispatchers.Default) { pageModels(models, query) }
    }
    val shown = page
    val missingSelected = selectedId != null &&
        Provider.fromModelId(selectedId) == activeProvider && models.none { it.id == selectedId }

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
                            error = r.exceptionOrNull()?.let { refreshErrorMessage(it) }
                        }
                    }) { Icon(Icons.Filled.Refresh, contentDescription = "Recharger la liste des modèles") }
                }
            }
            ProviderSegments(
                selected = pending ?: activeProvider,
                providersWithKey = providersWithKey,
                onSelect = { p ->
                    if (p in providersWithKey) {
                        pendingName = null
                        if (p != activeProvider) onSwitchProvider(p)
                    } else {
                        pendingName = p.name
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (pending != null) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Pas de clé ${pending.label}.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onAddKey(pending) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Ajouter une clé")
                    }
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
                        SimpleModelRow(
                            title = selectedId.substringAfterLast('/'),
                            subtitle = "Indisponible dans la liste actuelle",
                            onClick = { onSelect(selectedId) },
                        )
                    }
                }
                if (shown != null) {
                    items(shown.items, key = { it.id }) { m ->
                        ModelRow(
                            model = m,
                            plan = plan,
                            selected = m.id == selectedId,
                            onClick = { onSelect(m.id) },
                        )
                    }
                    if (shown.truncated) {
                        item(key = "truncated") {
                            Text(
                                "Affine ta recherche pour voir les autres (${formatInt(shown.total)} modèles)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            )
                        }
                    }
                    if (shown.items.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                if (models.isEmpty()) "Aucun modèle. Appuie sur ↻ pour charger la liste." else "Aucun résultat.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(20.dp),
                            )
                        }
                    }
                }
                item(key = "end") { Spacer(Modifier.size(16.dp)) }
            }
        }
    }
}

/** Message lisible (en français, via `toUserMessage()`) pour un échec de rechargement de la liste. */
internal fun refreshErrorMessage(t: Throwable): String =
    (t as? FireworksException)?.toUserMessage() ?: t.message ?: "Échec du rechargement des modèles."

@Composable
private fun SimpleModelRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Filled.Check, contentDescription = "Sélectionné", tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ModelRow(model: ModelInfo, plan: PlanInfo?, selected: Boolean, onClick: () -> Unit) {
    val fireworks = model.provider == Provider.FIREWORKS
    val offPlan = model.availableOnPlan == false
    val subtitle = when {
        model.gated -> GATED_HINT
        offPlan -> NOT_IN_PLAN_LABEL + " · " + modelSubtitle(model, plan)
        else -> modelSubtitle(model, plan)
    }
    val cost = model.concurrencyCost
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .alpha(if (offPlan) 0.5f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    model.shortId,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (fireworks) {
                    Spacer(Modifier.width(6.dp))
                    CostBadge(model.id, showUnknown = true)
                }
                if (cost != null && cost > 1) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "⚡×$cost",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.semantics { contentDescription = "Coût de concurrence $cost" },
                    )
                }
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (model.gated) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "Verrouillé",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
        }
        if (model.supportsImageInput) {
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
