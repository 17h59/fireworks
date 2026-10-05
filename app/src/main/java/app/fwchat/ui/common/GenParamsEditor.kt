package app.fwchat.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.fwchat.domain.AppDefaults
import app.fwchat.domain.GenParams
import app.fwchat.domain.ReasoningEffort

/**
 * Éditeur des paramètres de génération: UN contrôle par paramètre, tous visibles dans une colonne
 * (le parent la rend défilante). Chaque paramètre a un état « Défaut » (valeur null = non envoyée, le
 * modèle utilise sa propre valeur).
 *
 * Réutilisable tel quel dans l'écran Réglages (paramètres par défaut): mettre la colonne dans un
 * `verticalScroll`. Le composant est « contrôlé »: toute modification appelle [onChange] avec les nouveaux
 * paramètres complets; l'appelant décide de l'application (debounce, enregistrement...).
 *
 * @param modelId modèle ciblé (sert à masquer « Aucun » du raisonnement pour glm / gpt-oss), null = inconnu.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GenParamsEditor(
    params: GenParams,
    onChange: (GenParams) -> Unit,
    modelId: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(
            "Un paramètre sur « Défaut » n'est pas envoyé : le modèle utilise sa propre valeur.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        DecimalParam(
            title = "Température",
            value = params.temperature,
            range = 0.0..2.0,
            step = 0.05,
            initial = GenParamsLogic.INITIAL_TEMPERATURE,
            hint = "Plus haut = réponses plus variées.",
            onChange = { onChange(params.copy(temperature = it)) },
        )

        IntFieldParam(
            title = "Tokens max",
            value = params.maxTokens,
            initial = GenParamsLogic.INITIAL_MAX_TOKENS,
            hint = "Longueur maximale de la réponse (réflexion comprise).",
            onChange = { onChange(params.copy(maxTokens = it)) },
        )

        // Raisonnement
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Raisonnement", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (option in GenParamsLogic.reasoningOptions(modelId)) {
                    FilterChip(
                        selected = params.reasoningEffort == option,
                        onClick = { onChange(params.copy(reasoningEffort = option)) },
                        label = { Text(GenParamsLogic.reasoningLabel(option)) },
                    )
                }
            }
            Text(
                "Les modèles à raisonnement consomment des tokens max pour réfléchir.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        StopWordsParam(params = params, onChange = onChange)

        DecimalParam(
            title = "Top P",
            value = params.topP,
            range = 0.0..1.0,
            step = 0.01,
            initial = GenParamsLogic.INITIAL_TOP_P,
            hint = "Échantillonnage par noyau.",
            onChange = { onChange(params.copy(topP = it)) },
        )
        IntSliderParam(
            title = "Top K",
            value = params.topK,
            range = 0..100,
            initial = GenParamsLogic.INITIAL_TOP_K,
            hint = "Nombre de candidats considérés (0 = désactivé).",
            onChange = { onChange(params.copy(topK = it)) },
        )
        DecimalParam(
            title = "Min P",
            value = params.minP,
            range = 0.0..1.0,
            step = 0.01,
            initial = GenParamsLogic.INITIAL_MIN_P,
            hint = null,
            onChange = { onChange(params.copy(minP = it)) },
        )
        DecimalParam(
            title = "Pénalité de fréquence",
            value = params.frequencyPenalty,
            range = -2.0..2.0,
            step = 0.05,
            initial = GenParamsLogic.INITIAL_PENALTY,
            hint = null,
            onChange = { onChange(params.copy(frequencyPenalty = it)) },
        )
        DecimalParam(
            title = "Pénalité de présence",
            value = params.presencePenalty,
            range = -2.0..2.0,
            step = 0.05,
            initial = GenParamsLogic.INITIAL_PENALTY,
            hint = null,
            onChange = { onChange(params.copy(presencePenalty = it)) },
        )
        DecimalParam(
            title = "Pénalité de répétition",
            value = params.repetitionPenalty,
            range = 0.0..2.0,
            step = 0.05,
            initial = GenParamsLogic.INITIAL_REPETITION,
            hint = null,
            onChange = { onChange(params.copy(repetitionPenalty = it)) },
        )
        SeedParam(params = params, onChange = onChange)

        OutlinedButton(
            onClick = { onChange(AppDefaults.GEN_PARAMS) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Tout réinitialiser") }
    }
}

/**
 * Feuille du bas (une seule, défilante) contenant [GenParamsEditor].
 *
 * Contrôlée: [onChange] reçoit les paramètres complets à chaque modification (l'appelant gère le debounce / l'enregistrement),
 * [onDismiss] est appelé à la fermeture (bouton retour, geste ou toucher à l'extérieur).
 * « Tout réinitialiser » rappelle [onChange] avec `AppDefaults.GEN_PARAMS` (tokens max 16384, le reste sur « Défaut du modèle »).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenParamsSheet(
    params: GenParams,
    onChange: (GenParams) -> Unit,
    modelId: String?,
    onDismiss: () -> Unit,
    /** Sous-titre sous le titre de la feuille (null = aucun). */
    subtitle: String? = "Pour cette conversation",
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                Text("Paramètres de génération", style = MaterialTheme.typography.titleLarge)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            GenParamsEditor(params = params, onChange = onChange, modelId = modelId)
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Briques

@Composable
private fun ParamHeader(
    title: String,
    valueLabel: String?,
    isDefault: Boolean,
    onDefaultChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                valueLabel ?: "Défaut du modèle",
                style = MaterialTheme.typography.bodyMedium,
                color = if (valueLabel == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            )
        }
        FilterChip(
            selected = isDefault,
            onClick = { onDefaultChange(!isDefault) },
            label = { Text("Défaut") },
            leadingIcon = if (isDefault) {
                { Icon(Icons.Filled.Check, contentDescription = null) }
            } else null,
        )
    }
}

@Composable
private fun Hint(text: String?) {
    if (text != null) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DecimalParam(
    title: String,
    value: Double?,
    range: ClosedFloatingPointRange<Double>,
    step: Double,
    initial: Double,
    hint: String?,
    onChange: (Double?) -> Unit,
) {
    Column {
        ParamHeader(
            title = title,
            valueLabel = value?.let { GenParamsLogic.formatDecimal(it) },
            isDefault = value == null,
            onDefaultChange = { toDefault -> onChange(if (toDefault) null else initial) },
        )
        Slider(
            value = (value ?: initial).toFloat().coerceIn(range.start.toFloat(), range.endInclusive.toFloat()),
            onValueChange = { onChange(GenParamsLogic.snap(it.toDouble(), step).coerceIn(range)) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            enabled = value != null,
            modifier = Modifier.semantics { contentDescription = title },
        )
        Hint(hint)
    }
}

@Composable
private fun IntSliderParam(
    title: String,
    value: Int?,
    range: IntRange,
    initial: Int,
    hint: String?,
    onChange: (Int?) -> Unit,
) {
    Column {
        ParamHeader(
            title = title,
            valueLabel = value?.toString(),
            isDefault = value == null,
            onDefaultChange = { toDefault -> onChange(if (toDefault) null else initial) },
        )
        Slider(
            value = (value ?: initial).toFloat().coerceIn(range.first.toFloat(), range.last.toFloat()),
            onValueChange = { onChange(it.toInt().coerceIn(range)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            enabled = value != null,
            modifier = Modifier.semantics { contentDescription = title },
        )
        Hint(hint)
    }
}

@Composable
private fun IntFieldParam(
    title: String,
    value: Int?,
    initial: Int,
    hint: String?,
    onChange: (Int?) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value?.toString() ?: "") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ParamHeader(
            title = title,
            valueLabel = value?.toString(),
            isDefault = value == null,
            onDefaultChange = { toDefault -> onChange(if (toDefault) null else initial) },
        )
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                val digits = raw.filter { it.isDigit() }.take(9)
                text = digits
                val parsed = GenParamsLogic.parseInt(digits)
                // Un champ vidé ne repasse pas en « Défaut » (c'est le rôle de la pastille): on garde la valeur.
                if (parsed != null && parsed != value) onChange(parsed)
            },
            enabled = value != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Hint(hint)
    }
}

@Composable
private fun SeedParam(params: GenParams, onChange: (GenParams) -> Unit) {
    val value = params.seed
    var text by remember(value) { mutableStateOf(value?.toString() ?: "") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ParamHeader(
            title = "Seed",
            valueLabel = value?.toString(),
            isDefault = value == null,
            onDefaultChange = { toDefault ->
                onChange(params.copy(seed = if (toDefault) null else GenParamsLogic.INITIAL_SEED))
            },
        )
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                val cleaned = raw.filterIndexed { i, c -> c.isDigit() || (i == 0 && c == '-') }.take(18)
                text = cleaned
                val parsed = GenParamsLogic.parseLong(cleaned)
                if (parsed != null && parsed != value) onChange(params.copy(seed = parsed))
            },
            enabled = value != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Hint("Fixe l'aléa pour obtenir des réponses reproductibles.")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StopWordsParam(params: GenParams, onChange: (GenParams) -> Unit) {
    var input by remember { mutableStateOf("") }
    val full = params.stop.size >= GenParamsLogic.MAX_STOP
    fun commit() {
        if (input.isNotBlank()) {
            onChange(GenParamsLogic.addStop(params, input))
            input = ""
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Mots d'arrêt", style = MaterialTheme.typography.titleMedium)
        if (params.stop.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (word in params.stop) {
                    InputChip(
                        selected = false,
                        onClick = { onChange(GenParamsLogic.removeStop(params, word)) },
                        label = { Text(word, maxLines = 1) },
                        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Retirer « $word »") },
                    )
                }
            }
        }
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            enabled = !full,
            singleLine = true,
            placeholder = { Text(if (full) "4 mots d'arrêt maximum" else "Ajouter un mot d'arrêt") },
            trailingIcon = {
                IconButton(onClick = ::commit, enabled = !full && input.isNotBlank()) {
                    Icon(Icons.Filled.Add, contentDescription = "Ajouter le mot d'arrêt")
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Hint(if (params.stop.isEmpty()) "Défaut du modèle : aucun mot d'arrêt envoyé." else null)
    }
}
