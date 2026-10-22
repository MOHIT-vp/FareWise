package com.farewise.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Type scale tuned towards Apple's hierarchy: large, heavy titles with tight
 * negative tracking, 17 sp body copy, and only small labels left at neutral
 * spacing. Tracking matters more than font choice for that "crafted" feel.
 */
private fun style(
    size: Int,
    line: Int,
    weight: FontWeight,
    tracking: Double
) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp
)

val Typography = Typography(
    // Large navigation title ("FareWise", "My trips").
    headlineLarge = style(34, 41, FontWeight.Bold, -0.9),
    headlineMedium = style(28, 34, FontWeight.Bold, -0.6),
    headlineSmall = style(24, 30, FontWeight.Bold, -0.5),
    titleLarge = style(22, 28, FontWeight.Bold, -0.4),
    titleMedium = style(17, 22, FontWeight.SemiBold, -0.3),
    titleSmall = style(15, 20, FontWeight.SemiBold, -0.2),
    bodyLarge = style(17, 22, FontWeight.Normal, -0.3),
    bodyMedium = style(15, 20, FontWeight.Normal, -0.2),
    bodySmall = style(13, 18, FontWeight.Normal, -0.1),
    labelLarge = style(15, 20, FontWeight.SemiBold, -0.2),
    labelMedium = style(13, 16, FontWeight.Medium, 0.0),
    labelSmall = style(12, 16, FontWeight.Medium, 0.0)
)

/**
 * Tabular figures: every digit has the same advance width, so a price that
 * rolls from ₹374 to ₹381 doesn't shift the text around it.
 */
const val TABULAR_NUMBERS = "tnum"
