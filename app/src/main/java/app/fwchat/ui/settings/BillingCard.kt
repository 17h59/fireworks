package app.fwchat.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fwchat.domain.BillingRepository
import app.fwchat.domain.ModelPricing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Pancarte « Dépenses & crédit ». C'est le seul endroit de l'app où les dépenses sont affichées.
 * Rafraîchit à l'ouverture; [scope] (applicatif) laisse la lecture aller au bout si l'écran est quitté.
 */
@Composable
fun BillingCard(billing: BillingRepository, scope: CoroutineScope, modifier: Modifier = Modifier) {
    val state by billing.state.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    var inputError by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(billing) { scope.launch { billing.refresh() } }

    fun save() {
        val v = parseBalanceInput(input)
        if (v == null) {
            inputError = true
            return
        }
        editing = false
        inputError = false
        scope.launch { billing.setBalance(v) }
    }

    OutlinedCard(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Dépenses & crédit", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (state.loading) {
                    CircularProgressIndicator(Modifier.size(20.dp).padding(end = 0.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { scope.launch { billing.refresh() } }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Actualiser les dépenses", modifier = Modifier.size(20.dp))
                    }
                }
            }

            state.error?.let {
                Text(billingErrorMessage(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }

            Line("Dépenses ce mois", state.monthSpendUsd?.let(::formatUsd) ?: "—")
            state.monthlyCapUsd?.let { Line("Plafond mensuel", formatUsd(it)) }
            if (state.enteredBalance != null) {
                Line("Crédit estimé", state.estimatedCreditUsd?.let { "≈ ${formatUsd(it)}" } ?: "—")
                Text(
                    "Estimation à partir de ton solde saisi (dépenses Fireworks avec jusqu'à 1 jour de retard).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (editing) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; inputError = false },
                    label = { Text("Mon solde de crédit (USD)") },
                    singleLine = true,
                    isError = inputError,
                    supportingText = {
                        Text(if (inputError) "Saisis un montant valide, par exemple 12,50." else "Visible sur app.fireworks.ai/billing")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = ::save, modifier = Modifier.heightIn(min = 48.dp)) { Text("Enregistrer") }
                    TextButton(
                        onClick = { editing = false; inputError = false },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Annuler") }
                    if (state.enteredBalance != null) {
                        TextButton(
                            onClick = { editing = false; scope.launch { billing.setBalance(null) } },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("Oublier") }
                    }
                }
            } else {
                if (state.enteredBalance == null) {
                    Text(
                        "Fireworks ne donne pas le crédit restant. Saisis ton solde une fois (visible sur app.fireworks.ai/billing) " +
                            "et l'app estimera le reste.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = {
                        input = state.enteredBalance?.let { formatInput(it) } ?: ""
                        editing = true
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(if (state.enteredBalance == null) "Saisir mon solde" else "Modifier mon solde") }
            }

            Text(
                "Prix des modèles vérifiés le ${ModelPricing.PRICES_VERIFIED_LABEL} ; indicatifs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatInput(v: Double): String = "%.2f".format(java.util.Locale.FRANCE, v)

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
