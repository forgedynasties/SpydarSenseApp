package com.example.spydarsense.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Add these color definitions to enhance our palette
private val neutralLight = Color(0xFFF6F6F6)
private val neutralDark = Color(0xFF1D1D1D)

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80,
    surface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFF2D3133),
    outlineVariant = Color(0xFF3F4446)
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40,
    surface = Color(0xFFFBFBFB),
    surfaceVariant = Color(0xFFE7E7EC),
    outlineVariant = Color(0xFFD0D0D8)

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

// Create a theme state holder
object ThemeManager {
    val isDarkTheme = mutableStateOf(true)
}

// Create a composition local for theme state access
val LocalThemeState = staticCompositionLocalOf { ThemeManager.isDarkTheme }

@Composable
fun SpydarSenseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    // Use our ThemeManager for theme state if not set explicitly
    val themeState = LocalThemeState.current
    val isDark = if (darkTheme) true else themeState.value
    
    // Create animated color transitions
    val animationSpec = tween<Color>(durationMillis = 300)
    val primaryColor = animateColorAsState(
        targetValue = if (isDark) DarkColorScheme.primary else LightColorScheme.primary,
        animationSpec = animationSpec,
        label = "primary"
    )
    val secondaryColor = animateColorAsState(
        targetValue = if (isDark) DarkColorScheme.secondary else LightColorScheme.secondary,
        animationSpec = animationSpec,
        label = "secondary"
    )
    val backgroundColor = animateColorAsState(
        targetValue = if (isDark) DarkColorScheme.background else LightColorScheme.background,
        animationSpec = animationSpec,
        label = "background"
    )
    
    // Choose the appropriate color scheme
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        isDark -> DarkColorScheme
        else -> LightColorScheme
    }
    
    // Apply theme to the system UI
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDark
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

// Helper composable to toggle theme
@Composable
fun rememberThemeState(): MutableState<Boolean> {
    return remember { ThemeManager.isDarkTheme }
}