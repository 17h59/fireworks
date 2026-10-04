package app.fwchat.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Palette de repli (API < 31 ou couleurs dynamiques indisponibles). Sur minSdk 34 les
// couleurs dynamiques sont toujours disponibles; ces schémas servent de filet de sécurité.
internal val FireOrange = Color(0xFFB45309)
internal val FireOrangeDark = Color(0xFFFFB84D)

internal val FallbackLightColors = lightColorScheme(
    primary = FireOrange,
    background = Color(0xFFFFFBFB),
    surface = Color(0xFFFFFBFB),
)

internal val FallbackDarkColors = darkColorScheme(
    primary = FireOrangeDark,
    background = Color(0xFF141218),
    surface = Color(0xFF141218),
)
