package app.fwchat.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fwchat.R
import app.fwchat.domain.Provider
import app.fwchat.ui.common.ProviderSegments
import app.fwchat.ui.theme.FwMark
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = viewModel(factory = OnboardingViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // rememberSaveable: la clé survit à la rotation, mais jamais écrite ailleurs que dans le Bundle d'état de l'activité.
    var key by rememberSaveable { mutableStateOf("") }
    var providerName by rememberSaveable { mutableStateOf(Provider.FIREWORKS.name) }
    val provider = Provider.entries.firstOrNull { it.name == providerName } ?: Provider.FIREWORKS
    var visible by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.done) {
        if (state.done) onDone()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        // verticalScroll donne une hauteur illimitée au contenu: on impose au moins la hauteur disponible
        // pour centrer verticalement quand il y a la place, tout en restant défilant avec le clavier.
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
            val minHeight = maxHeight
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = minHeight)
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                FwMark(size = 72.dp)
                Text(
                    text = stringResource(R.string.onboarding_title),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Bullet(stringResource(R.string.onboarding_point_local))
                    Bullet(stringResource(R.string.onboarding_point_key))
                    Bullet(stringResource(R.string.onboarding_point_account))
                }
                Text(
                    text = "Choisis ton fournisseur. Tu pourras en ajouter un second plus tard dans les Réglages.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ProviderSegments(
                    selected = provider,
                    providersWithKey = Provider.entries.toSet(),
                    onSelect = {
                        if (it != provider && !state.loading) {
                            providerName = it.name
                            viewModel.clearError()
                        }
                    },
                )
                Text(
                    text = onboardingHelp(provider),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it; viewModel.clearError() },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Clé API ${provider.label}") },
                    singleLine = true,
                    enabled = !state.loading,
                    isError = state.error != null,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onDone = { viewModel.submit(provider, key) },
                    ),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = stringResource(
                                    if (visible) R.string.onboarding_hide else R.string.onboarding_show,
                                ),
                            )
                        }
                    },
                    supportingText = state.message?.let { msg -> { Text(msg) } },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        enabled = !state.loading,
                        onClick = {
                            scope.launch {
                                val text = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }
                                    ?.getItemAt(0)?.text?.toString()
                                if (!text.isNullOrBlank()) {
                                    key = text.trim()
                                    viewModel.clearError()
                                }
                            }
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.onboarding_paste)) }
                    TextButton(
                        onClick = { uriHandler.openUri(providerSiteUrl(provider)) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Ouvrir ${providerSiteName(provider)}") }
                }
                Button(
                    onClick = { viewModel.submit(provider, key) },
                    enabled = !state.loading && key.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    if (state.loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(stringResource(R.string.onboarding_validate))
                    }
                }
            }
        }
    }
}

/** Adresse du site où créer une clé. */
internal fun providerSiteUrl(provider: Provider): String = "https://" + providerSiteName(provider)

internal fun providerSiteName(provider: Provider): String = when (provider) {
    Provider.FIREWORKS -> "fireworks.ai"
    Provider.FEATHERLESS -> "featherless.ai"
}

/** Texte d'aide adapté au fournisseur choisi. */
internal fun onboardingHelp(provider: Provider): String = when (provider) {
    Provider.FIREWORKS ->
        "Crée une clé sur fireworks.ai (section Clés API de ton compte), puis colle-la ci-dessous. Facturation à l'usage."
    Provider.FEATHERLESS ->
        "Crée une clé sur featherless.ai (section API Keys de ton compte), puis colle-la ci-dessous. Abonnement forfaitaire."
}

/** Puce de réassurance: coche + texte court. */
@Composable
private fun Bullet(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
