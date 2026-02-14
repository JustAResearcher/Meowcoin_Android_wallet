package com.meowcoin.wallet.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = MeowOrange,
    onPrimary = Color.White,
    primaryContainer = MeowOrangeDark,
    onPrimaryContainer = Color.White,
    secondary = MeowAmber,
    onSecondary = Color.Black,
    background = MeowDarkBg,
    onBackground = MeowLightText,
    surface = MeowDarkSurface,
    onSurface = MeowLightText,
    surfaceVariant = MeowDarkSurfaceVariant,
    onSurfaceVariant = MeowGray,
    error = MeowRed,
    onError = Color.White,
    outline = MeowGray
)

private val LightColorScheme = lightColorScheme(
    primary = MeowOrange,
    onPrimary = Color.White,
    primaryContainer = MeowOrangeLight,
    onPrimaryContainer = Color.Black,
    secondary = MeowAmber,
    onSecondary = Color.Black,
    background = MeowLightBg,
    onBackground = MeowDarkText,
    surface = MeowLightSurface,
    onSurface = MeowDarkText,
    surfaceVariant = MeowLightSurfaceVariant,
    onSurfaceVariant = MeowSubtleText,
    error = MeowRed,
    onError = Color.White,
    outline = MeowGray
)

val MeowTypography = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        letterSpacing = 0.15.sp
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        letterSpacing = 0.25.sp
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        letterSpacing = 0.1.sp
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.5.sp
    )
)

@Composable
fun MeowcoinWalletTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MeowTypography,
        content = content
    )
}
