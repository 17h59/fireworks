package app.fwchat.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {

    private fun lin(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }

    private fun luminance(c: Color) = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** Paires texte/fond: AA (4,5) exigé. */
    private fun textPairs(c: ColorScheme) = mapOf(
        "onPrimary/primary" to (c.onPrimary to c.primary),
        "onPrimaryContainer/primaryContainer" to (c.onPrimaryContainer to c.primaryContainer),
        "onSecondary/secondary" to (c.onSecondary to c.secondary),
        "onSecondaryContainer/secondaryContainer" to (c.onSecondaryContainer to c.secondaryContainer),
        "onTertiary/tertiary" to (c.onTertiary to c.tertiary),
        "onTertiaryContainer/tertiaryContainer" to (c.onTertiaryContainer to c.tertiaryContainer),
        "onError/error" to (c.onError to c.error),
        "onErrorContainer/errorContainer" to (c.onErrorContainer to c.errorContainer),
        "onSurface/surface" to (c.onSurface to c.surface),
        "onBackground/background" to (c.onBackground to c.background),
        "onSurfaceVariant/surfaceVariant" to (c.onSurfaceVariant to c.surfaceVariant),
        "onSurfaceVariant/surfaceContainer" to (c.onSurfaceVariant to c.surfaceContainer),
        "onSurface/surfaceContainerHigh" to (c.onSurface to c.surfaceContainerHigh),
        "inverseOnSurface/inverseSurface" to (c.inverseOnSurface to c.inverseSurface),
        // Texte coloré sur la surface (liens, titres de section, étoile, spinner).
        "primary/surface" to (c.primary to c.surface),
        "secondary/surface" to (c.secondary to c.surface),
        "tertiary/surface" to (c.tertiary to c.surface),
        "error/surface" to (c.error to c.surface),
        "primary/primaryContainer" to (c.primary to c.primaryContainer),
    )

    @Test
    fun `les paires texte fond atteignent AA en clair`() = check(FwLightColors)

    @Test
    fun `les paires texte fond atteignent AA en sombre`() = check(FwDarkColors)

    private fun check(c: ColorScheme) {
        for ((name, pair) in textPairs(c)) {
            val ratio = contrast(pair.first, pair.second)
            assertTrue("$name: contraste $ratio < 4.5", ratio >= 4.5)
        }
        // Éléments d'interface (contours): 3:1.
        assertTrue(contrast(c.outline, c.surface) >= 3.0)
    }

    @Test
    fun `les fonds de fenetre correspondent a la surface de la marque`() {
        assertEquals(Color(0xFFFFFBFE), FwLightColors.background)
        assertEquals(Color(0xFF141218), FwDarkColors.background)
        assertEquals(FwLightColors.background, FwLightColors.surface)
        assertEquals(FwDarkColors.background, FwDarkColors.surface)
    }

    @Test
    fun `les accents suivent la marque`() {
        assertEquals(Color(0xFF5A3FE0), FwLightColors.primary)
        assertEquals(Color(0xFFC8BDFF), FwDarkColors.primary)
        assertEquals(Color(0xFFFFFFFF), FwLightColors.onPrimary)
        assertEquals(Color(0xFF2B1A8F), FwDarkColors.onPrimary)
        assertEquals(Color(0xFF9A4A00), FwLightColors.tertiary)
        assertEquals(Color(0xFFFFB77A), FwDarkColors.tertiary)
    }
}
