package com.farewise.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/** Corner radius shared by every card so the list reads as one system. */
val CardShape = RoundedCornerShape(20.dp)

/**
 * Card background with a large, faint, diffuse shadow (instead of Material's
 * tight elevation shadow). In dark mode shadows vanish, so a hairline light
 * border separates the card from the background instead.
 */
fun Modifier.softCard(shape: Shape = CardShape): Modifier = composed {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.5f
    if (isDark) {
        this
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, Color.White.copy(alpha = 0.06f), shape)
    } else {
        this
            .shadow(
                elevation = 14.dp,
                shape = shape,
                ambientColor = Color(0xFF1B1D3A).copy(alpha = 0.04f),
                spotColor = Color(0xFF1B1D3A).copy(alpha = 0.10f)
            )
            .background(colors.surface, shape)
            .clip(shape)
    }
}
