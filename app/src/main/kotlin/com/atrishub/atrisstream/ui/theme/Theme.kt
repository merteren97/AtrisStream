package com.atrishub.atrisstream.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Cinematic dark Android TV color palette for AtrisStream.
 */
object AtrisColors {
    val Background = Color(0xFF0B0E14)
    val Surface = Color(0xFF141923)
    val ElevatedCard = Color(0xFF1C2331)
    val CardBorder = Color(0xFF283347)
    val AccentCyan = Color(0xFF38BDF8)
    val SecondaryIndigo = Color(0xFF818CF8)
    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFF94A3B8)
    val Success = Color(0xFF22C55E)
    val Error = Color(0xFFEF4444)
    val Warning = Color(0xFFF59E0B)
}

private val AtrisDarkColorScheme = darkColorScheme(
    primary = AtrisColors.AccentCyan,
    onPrimary = AtrisColors.Background,
    secondary = AtrisColors.SecondaryIndigo,
    onSecondary = AtrisColors.Background,
    background = AtrisColors.Background,
    onBackground = AtrisColors.TextPrimary,
    surface = AtrisColors.Surface,
    onSurface = AtrisColors.TextPrimary,
    surfaceVariant = AtrisColors.ElevatedCard,
    onSurfaceVariant = AtrisColors.TextSecondary,
    error = AtrisColors.Error,
    onError = AtrisColors.TextPrimary
)

private val AtrisTypography = Typography(
    headlineLarge = TextStyle(
        fontSize = 32.sp,
        fontWeight = FontWeight.Bold,
        color = AtrisColors.TextPrimary,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        color = AtrisColors.TextPrimary
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        color = AtrisColors.TextPrimary
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        color = AtrisColors.TextPrimary
    ),
    bodyLarge = TextStyle(
        fontSize = 15.sp,
        color = AtrisColors.TextPrimary,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        color = AtrisColors.TextSecondary,
        lineHeight = 20.sp
    ),
    labelLarge = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = AtrisColors.TextPrimary
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = AtrisColors.TextSecondary
    )
)

@Composable
fun AtrisStreamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AtrisDarkColorScheme,
        typography = AtrisTypography,
        content = content
    )
}
