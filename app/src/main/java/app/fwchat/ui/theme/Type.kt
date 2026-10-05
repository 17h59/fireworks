package app.fwchat.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight

private val Base = Typography()

/** Typographie Material3 par défaut, avec des titres en graisse Medium. */
val AppTypography = Typography(
    displayLarge = Base.displayLarge.copy(fontWeight = FontWeight.Medium),
    displayMedium = Base.displayMedium.copy(fontWeight = FontWeight.Medium),
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.Medium),
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.Medium),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.Medium),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.Medium),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.Medium),
)
