package app.fwchat.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.fwchat.domain.PlanInfo
import app.fwchat.domain.Provider
import app.fwchat.ui.common.formatContext

/** État textuel d'une clé: « Enregistrée ••••abcd » / « Absente » (+ « · mode actif »). */
fun keyStatusLabel(hasKey: Boolean, hint: String?, active: Boolean): String {
    val base = if (hasKey) "Enregistrée " + (hint ?: "••••") else "Absente"
    return if (active && hasKey) "$base · mode actif" else base
}

/**
 * Ligne compacte d'un fournisseur: état de la clé, « Ajouter » / « Remplacer » (champ masqué, validation) et
 * « Supprimer ». Un seul champ est ouvert à la fois (géré par l'appelant via [editing]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProviderKeyRow(
    provider: Provider,
    active: Boolean,
    hasKey: Boolean,
    hint: String?,
    editing: Boolean,
    validating: Boolean,
    error: String?,
    newKey: String,
    keyVisible: Boolean,
    onNewKeyChange: (String) -> Unit,
    onToggleVisible: () -> Unit,
    onStartEdit: () -> Unit,
    onCancel: () -> Unit,
    onPaste: () -> Unit,
    onSubmit: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(keyTitle(provider), style = MaterialTheme.typography.bodyLarge)
                Text(
                    keyStatusLabel(hasKey, hint, active),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (editing) {
            OutlinedTextField(
                value = newKey,
                onValueChange = onNewKeyChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                label = { Text(if (hasKey) "Nouvelle clé ${provider.label}" else "Clé API ${provider.label}") },
                singleLine = true,
                enabled = !validating,
                isError = error != null,
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                trailingIcon = {
                    IconButton(onClick = onToggleVisible) {
                        Icon(
                            imageVector = if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (keyVisible) "Masquer la clé" else "Afficher la clé",
                        )
                    }
                },
                supportingText = error?.let { err -> { Text(err) } },
            )
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                TextButton(enabled = !validating, modifier = Modifier.heightIn(min = 48.dp), onClick = onPaste) {
                    Text("Coller")
                }
                TextButton(enabled = !validating, modifier = Modifier.heightIn(min = 48.dp), onClick = onCancel) {
                    Text("Annuler")
                }
                Button(
                    onClick = onSubmit,
                    enabled = !validating && newKey.isNotBlank(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    if (validating) {
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
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedButton(onClick = onStartEdit, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (hasKey) "Remplacer" else "Ajouter")
                }
                if (hasKey) {
                    TextButton(onClick = onRemove, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Supprimer", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

/** Carte « Plan Featherless »: nom, concurrence, contexte max, crédits éventuels, états de chargement et d'erreur. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlanCard(plan: PlanInfo?, loading: Boolean, error: String?, onReload: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            plan != null -> {
                Text(plan.name.ifBlank { "Plan Featherless" }, style = MaterialTheme.typography.titleMedium)
                plan.concurrency?.let { PlanLine("Concurrence", "$it ${if (it > 1) "unités" else "unité"}") }
                plan.maxContextLength?.let { ctx -> PlanLine("Contexte max", formatContext(ctx) ?: ctx.toString()) }
                plan.credits?.let { PlanLine("Crédits", formatCredits(it)) }
            }
            loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("  Chargement du plan…", style = MaterialTheme.typography.bodyMedium)
            }
            error == null -> Text("Plan indisponible.", style = MaterialTheme.typography.bodyMedium)
        }
        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        Text(
            PLAN_FLAT_RATE_NOTE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = onReload,
            enabled = !loading,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text(if (error != null) "Réessayer" else "Actualiser le plan") }
    }
}

@Composable
private fun PlanLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

const val PLAN_FLAT_RATE_NOTE =
    "Abonnement forfaitaire: la limite est la concurrence (nombre de réponses simultanées selon la taille du modèle)."

/** Crédits Featherless sans symbole monétaire: 12 -> « 12 », 12.5 -> « 12,5 ». */
fun formatCredits(v: Double): String =
    if (v == Math.floor(v) && v < 1e9) v.toLong().toString() else String.format(java.util.Locale.FRANCE, "%.2f", v)
