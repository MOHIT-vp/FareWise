package com.farewise.app.ui.dashboard.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.farewise.app.ui.theme.DropGreen
import com.farewise.app.ui.theme.SurgeRed

/**
 * A tiny line chart of recent prices for a single tier, drawn at card-strip
 * scale (no axis, no labels — just shape).
 *
 * Stroke colour is derived from trend:
 *   • last point ≥ 5% below first  → green (price falling, good time to wait)
 *   • last point ≥ 5% above first  → red   (price rising, book now)
 *   • otherwise                    → muted onSurfaceVariant (flat)
 *
 * A faint area fill underneath gives the line some weight at small sizes
 * without competing with the rest of the card. The series is min/max
 * normalised so the same shape renders cleanly whether the prices are
 * ₹50–₹70 or ₹400–₹600.
 */
@Composable
fun PriceSparkline(
    prices: List<Double>,
    modifier: Modifier = Modifier
) {
    // Need at least two points to draw a line at all.
    if (prices.size < 2) return

    val minValue = prices.min()
    val maxValue = prices.max()
    val range = (maxValue - minValue).coerceAtLeast(1e-3)

    val first = prices.first()
    val last = prices.last()
    val deltaPct = (last - first) / first.coerceAtLeast(1e-3)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val color = when {
        deltaPct <= -TREND_THRESHOLD -> DropGreen
        deltaPct >= TREND_THRESHOLD -> SurgeRed
        else -> muted
    }
    val fill = Brush.verticalGradient(
        colors = listOf(color.copy(alpha = 0.22f), Color.Transparent)
    )

    Canvas(
        modifier = modifier
            .height(28.dp)
            .padding(vertical = 2.dp)
    ) {
        val w = size.width
        val h = size.height
        val stepX = if (prices.size > 1) w / (prices.size - 1) else w

        val line = Path()
        val area = Path()
        prices.forEachIndexed { i, value ->
            val x = i * stepX
            // Y axis inverted: low price = high on screen.
            val yNorm = ((value - minValue) / range).toFloat()
            val y = h - yNorm * h
            if (i == 0) {
                line.moveTo(x, y)
                area.moveTo(x, h)
                area.lineTo(x, y)
            } else {
                line.lineTo(x, y)
                area.lineTo(x, y)
            }
        }
        area.lineTo(w, h)
        area.close()

        drawPath(area, brush = fill)
        drawPath(line, color = color, style = Stroke(width = STROKE_PX))
        // Dot the latest price so it pops at this scale.
        drawCircle(
            color = color,
            radius = DOT_RADIUS_PX,
            center = lastPoint(prices, stepX, h, minValue, range)
        )
    }
}

private fun lastPoint(
    prices: List<Double>,
    stepX: Float,
    h: Float,
    minValue: Double,
    range: Double
): Offset {
    val i = prices.lastIndex
    val x = i * stepX
    val yNorm = ((prices.last() - minValue) / range).toFloat()
    val y = h - yNorm * h
    return Offset(x, y)
}

private const val TREND_THRESHOLD = 0.05  // 5% over the visible window
private const val STROKE_PX = 2.2f
private const val DOT_RADIUS_PX = 3.0f
