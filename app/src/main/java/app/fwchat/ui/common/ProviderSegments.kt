package app.fwchat.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.fwchat.domain.Provider

/** Libellé accessible d'un segment: « Fireworks, sélectionné » / « Featherless, sans clé ». */
fun providerSegmentDescription(provider: Provider, selected: Boolean, hasKey: Boolean): String =
    provider.label + when {
        !hasKey -> ", sans clé"
        selected -> ", sélectionné"
        else -> ""
    }

/** Segment « Fireworks | Featherless »: change de mode d'un geste. [onSelect] reçoit aussi les fournisseurs sans clé. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderSegments(
    selected: Provider,
    providersWithKey: Set<Provider>,
    onSelect: (Provider) -> Unit,
    modifier: Modifier = Modifier,
) {
    val all = Provider.entries
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        all.forEachIndexed { index, provider ->
            val isSelected = provider == selected
            val hasKey = provider in providersWithKey
            SegmentedButton(
                selected = isSelected,
                onClick = { onSelect(provider) },
                shape = SegmentedButtonDefaults.itemShape(index, all.size),
                modifier = Modifier.semantics { contentDescription = providerSegmentDescription(provider, isSelected, hasKey) },
            ) { Text(provider.label) }
        }
    }
}
