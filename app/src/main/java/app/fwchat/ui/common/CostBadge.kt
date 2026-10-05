package app.fwchat.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import app.fwchat.domain.costDescription
import app.fwchat.domain.costIcons

/**
 * Petites icônes « $ » (1 à 4) indiquant le coût relatif d'un modèle. Rien n'est affiché si le prix est inconnu,
 * sauf si [showUnknown] (un « ? » très discret, réservé au sélecteur de modèle).
 */
@Composable
fun CostBadge(
    modelId: String,
    modifier: Modifier = Modifier,
    showUnknown: Boolean = false,
    fontSize: TextUnit = 11.sp,
) {
    val icons = costIcons(modelId)
    val text = icons ?: if (showUnknown) "?" else return
    val description = costDescription(modelId) ?: "Coût inconnu"
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontSize = fontSize,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        softWrap = false,
        modifier = modifier.semantics { contentDescription = description },
    )
}
