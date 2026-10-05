package app.fwchat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/** Thème de marque (clair/sombre selon le système). Pas de couleurs dynamiques: voir [FwLightColors]. */
@Composable
fun FwChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) FwDarkColors else FwLightColors,
        typography = AppTypography,
        content = content,
    )
}
