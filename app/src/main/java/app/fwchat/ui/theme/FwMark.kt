package app.fwchat.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Marque de l'app: la gerbe de 6 rayons ambre et son cœur blanc de l'icône, sur la pastille indigo nuit.
 * Dessinée en vectoriel: nette à toutes les tailles (24 dp dans le tiroir, 72 dp à l'accueil).
 */
@Composable
fun FwMark(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size.minDimension
        drawRoundRect(color = BrandNight, cornerRadius = CornerRadius(s * 0.28f))
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val unit = s / 108f * 1.35f // même tracé que l'icône (grille de 108), un peu plus grand dans la pastille
        val stroke = 5f * unit
        for (i in 0 until 6) {
            val angle = Math.toRadians(-90.0 + 60.0 * i)
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            drawLine(
                color = BrandSpark,
                start = Offset(center.x + dx * 11f * unit, center.y + dy * 11f * unit),
                end = Offset(center.x + dx * 29f * unit, center.y + dy * 29f * unit),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
        drawCircle(color = Color.White, radius = 6.5f * unit, center = center)
    }
}
