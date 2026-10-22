package com.farewise.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = BrandIndigoLight,
    onPrimary = Color(0xFF101033),
    primaryContainer = BrandIndigoDark,
    onPrimaryContainer = BrandIndigoLight,
    secondary = TransitTealLight,
    onSecondary = Color(0xFF003833),
    tertiary = SurgeAmber,
    onTertiary = Color(0xFF2C1A00),
    background = Surface0Dark,
    onBackground = OnSurfaceDark,
    surface = Surface1Dark,
    onSurface = OnSurfaceDark,
    surfaceVariant = Surface2Dark,
    onSurfaceVariant = OnSurfaceVariantDark,
    error = SurgeRed
)

private val LightColorScheme = lightColorScheme(
    primary = BrandIndigo,
    onPrimary = Color.White,
    primaryContainer = BrandIndigoLight,
    onPrimaryContainer = Color(0xFF10103A),
    secondary = TransitTeal,
    onSecondary = Color.White,
    tertiary = SurgeAmber,
    onTertiary = Color(0xFF2C1A00),
    background = Surface0Light,
    onBackground = OnSurfaceLight,
    surface = Surface1Light,
    onSurface = OnSurfaceLight,
    surfaceVariant = Surface2Light,
    onSurfaceVariant = OnSurfaceVariantLight,
    error = SurgeRed
)

@Composable
fun FareWiseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color (Android 12+) is disabled by default so the brand
    // palette stays consistent in the screenshots and demo. Flip to true
    // to opt back into Material You.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // enableEdgeToEdge() makes the status bar transparent, so only the
            // icon tint needs to follow the theme.
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
