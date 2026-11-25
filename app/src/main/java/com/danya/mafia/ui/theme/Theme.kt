package com.danya.mafia.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val MafiaColors = darkColorScheme(
    primary = BloodRed,
    onPrimary = BloodRedDark,
    primaryContainer = BloodRedContainer,
    onPrimaryContainer = OnBloodRedContainer,
    inversePrimary = ColorTokens.inversePrimary,
    secondary = Gold,
    onSecondary = GoldDark,
    secondaryContainer = GoldContainer,
    onSecondaryContainer = OnGoldContainer,
    tertiary = ColorTokens.tertiary,
    onTertiary = ColorTokens.onTertiary,
    tertiaryContainer = ColorTokens.tertiaryContainer,
    onTertiaryContainer = ColorTokens.onTertiaryContainer,
    background = Ink,
    onBackground = Paper,
    surface = Ink,
    onSurface = Paper,
    surfaceVariant = InkHigh,
    onSurfaceVariant = PaperMuted,
    surfaceTint = BloodRed,
    inverseSurface = Paper,
    inverseOnSurface = InkRaised,
    outline = OutlineMuted,
    outlineVariant = ColorTokens.outlineVariant,
    error = BloodRed,
    onError = BloodRedDark,
    errorContainer = BloodRedContainer,
    onErrorContainer = OnBloodRedContainer,
    scrim = ColorTokens.scrim
)

private object ColorTokens {
    val inversePrimary = androidx.compose.ui.graphics.Color(0xFF8B1A1A)
    val tertiary = androidx.compose.ui.graphics.Color(0xFFB8C4FF)
    val onTertiary = androidx.compose.ui.graphics.Color(0xFF1F2A5C)
    val tertiaryContainer = androidx.compose.ui.graphics.Color(0xFF374379)
    val onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFFDDE1FF)
    val outlineVariant = androidx.compose.ui.graphics.Color(0xFF49454F)
    val scrim = androidx.compose.ui.graphics.Color(0xFF000000)
}

private val MafiaTypography = Typography().copy(
    displayLarge = TextStyle(
        fontWeight = FontWeight.Black,
        fontSize = 64.sp,
        lineHeight = 68.sp,
        letterSpacing = 10.sp
    ),
    displayMedium = TextStyle(
        fontWeight = FontWeight.Black,
        fontSize = 42.sp,
        lineHeight = 48.sp,
        letterSpacing = 5.sp
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    )
)

@Composable
fun MafiaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MafiaColors,
        typography = MafiaTypography,
        content = content
    )
}
