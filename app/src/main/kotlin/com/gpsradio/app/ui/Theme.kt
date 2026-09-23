package com.gpsradio.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Station brand colours outside the M3 roles. */
object Brand {
    val onAir = Color(0xFFC8102E)
    val onAirDark = Color(0xFFFF4D5E)
}

// Full M3 scheme (UX audit round 2): deep teal primary, warm amber secondary, on-air red tertiary,
// warm neutral surfaces, so no container falls back to the purple template.
private val Light = lightColorScheme(
    primary = Color(0xFF0F6B62), onPrimary = Color.White,
    primaryContainer = Color(0xFFBFEDE4), onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF8A4B0F), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDCC0), onSecondaryContainer = Color(0xFF2E1500),
    tertiary = Color(0xFFB3202E), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDAD8), onTertiaryContainer = Color(0xFF410006),
    background = Color(0xFFFBF9F4), onBackground = Color(0xFF1B1C1A),
    surface = Color(0xFFFBF9F4), onSurface = Color(0xFF1B1C1A),
    surfaceVariant = Color(0xFFDAE5E1), onSurfaceVariant = Color(0xFF3F4946),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF4F1EB),
    surfaceContainer = Color(0xFFEEEBE5), surfaceContainerHigh = Color(0xFFE8E5DF),
    surfaceContainerHighest = Color(0xFFE2DFD9),
    outline = Color(0xFF6F7976), outlineVariant = Color(0xFFBEC9C5),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF7ED8CA), onPrimary = Color(0xFF003733),
    primaryContainer = Color(0xFF005049), onPrimaryContainer = Color(0xFF9EF2E3),
    secondary = Color(0xFFFFB876), onSecondary = Color(0xFF4A2800),
    secondaryContainer = Color(0xFF6A3B00), onSecondaryContainer = Color(0xFFFFDCC0),
    tertiary = Color(0xFFFFB3B0), onTertiary = Color(0xFF68000F),
    tertiaryContainer = Color(0xFF93000F), onTertiaryContainer = Color(0xFFFFDAD8),
    background = Color(0xFF101413), onBackground = Color(0xFFE0E3E1),
    surface = Color(0xFF101413), onSurface = Color(0xFFE0E3E1),
    surfaceVariant = Color(0xFF3F4946), onSurfaceVariant = Color(0xFFBEC9C5),
    surfaceContainerLowest = Color(0xFF0B0F0E), surfaceContainerLow = Color(0xFF191D1C),
    surfaceContainer = Color(0xFF1C2321), surfaceContainerHigh = Color(0xFF262D2B),
    surfaceContainerHighest = Color(0xFF313836),
    outline = Color(0xFF89938F), outlineVariant = Color(0xFF3F4946),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Default type with a firmer title hierarchy and a 12 sp floor for small labels. */
private fun appTypography(): Typography {
    val t = Typography()
    return t.copy(
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.25).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = t.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
    )
}

@Composable
fun GpsRadioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = AppShapes,
        typography = appTypography(),
        content = content,
    )
}
