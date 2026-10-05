package app.fwchat.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * Identité visuelle « nuit de feu d'artifice »: un violet-indigo (primary) et, plus rarement, une braise
 * ambrée (secondary/tertiary: spinner de réflexion, étoile de prompt par défaut, indicateur de génération).
 * Les surfaces sont des neutres légèrement teintés. Les couleurs dynamiques (Material You) ne sont pas
 * utilisées: l'app garde la même allure sur tous les téléphones.
 */

// --- Marque ---------------------------------------------------------------------------------------
internal val BrandIndigo = Color(0xFF5A3FE0)
internal val BrandLilac = Color(0xFFC8BDFF)
internal val BrandEmber = Color(0xFF9A4A00)
internal val BrandEmberLight = Color(0xFFFFB77A)

/** Fond de l'icône et de la marque (identique à `ic_launcher_background`). */
internal val BrandNight = Color(0xFF1B1530)

/** Ambre des rayons de la gerbe (identique à l'icône). */
internal val BrandSpark = Color(0xFFFFB84D)

val FwLightColors: ColorScheme = lightColorScheme(
    primary = BrandIndigo,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE5DEFF),
    onPrimaryContainer = Color(0xFF1B0A6B),
    inversePrimary = BrandLilac,
    secondary = BrandEmber,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8E0F5),
    onSecondaryContainer = Color(0xFF1D192B),
    tertiary = BrandEmber,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDCC2),
    onTertiaryContainer = Color(0xFF321300),
    background = Color(0xFFFFFBFE),
    onBackground = Color(0xFF1D1B20),
    surface = Color(0xFFFFFBFE),
    onSurface = Color(0xFF1D1B20),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    surfaceTint = BrandIndigo,
    inverseSurface = Color(0xFF322F35),
    inverseOnSurface = Color(0xFFF5EFF7),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFBFE),
    surfaceDim = Color(0xFFDED8E1),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F2FA),
    surfaceContainer = Color(0xFFF3EDF7),
    surfaceContainerHigh = Color(0xFFECE6F0),
    surfaceContainerHighest = Color(0xFFE6E0E9),
)

val FwDarkColors: ColorScheme = darkColorScheme(
    primary = BrandLilac,
    onPrimary = Color(0xFF2B1A8F),
    primaryContainer = Color(0xFF4328C0),
    onPrimaryContainer = Color(0xFFE5DEFF),
    inversePrimary = BrandIndigo,
    secondary = BrandEmberLight,
    onSecondary = Color(0xFF4E2600),
    secondaryContainer = Color(0xFF332D41),
    onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = BrandEmberLight,
    onTertiary = Color(0xFF4E2600),
    tertiaryContainer = Color(0xFF6F3700),
    onTertiaryContainer = Color(0xFFFFDCC2),
    background = Color(0xFF141218),
    onBackground = Color(0xFFE6E0E9),
    surface = Color(0xFF141218),
    onSurface = Color(0xFFE6E0E9),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    surfaceTint = BrandLilac,
    inverseSurface = Color(0xFFE6E0E9),
    inverseOnSurface = Color(0xFF322F35),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF3B383E),
    surfaceDim = Color(0xFF141218),
    surfaceContainerLowest = Color(0xFF0F0D13),
    surfaceContainerLow = Color(0xFF1D1B20),
    surfaceContainer = Color(0xFF211F26),
    surfaceContainerHigh = Color(0xFF2B2930),
    surfaceContainerHighest = Color(0xFF36343B),
)
