package com.example.aiinterviewapp.ui.theme

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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val SherifDarkColorScheme = darkColorScheme(
    primary = SherifPrimaryDark,
    onPrimary = SherifOnPrimaryDark,
    primaryContainer = SherifPrimaryContainerDark,
    onPrimaryContainer = SherifOnPrimaryContainerDark,
    secondary = SherifSecondaryDark,
    onSecondary = SherifOnSecondaryDark,
    secondaryContainer = SherifSecondaryContainerDark,
    onSecondaryContainer = SherifOnSecondaryContainerDark,
    tertiary = SherifTertiaryDark,
    onTertiary = SherifOnTertiaryDark,
    tertiaryContainer = SherifTertiaryContainerDark,
    onTertiaryContainer = SherifOnTertiaryContainerDark,
    background = SherifBackgroundDark,
    onBackground = SherifOnBackgroundDark,
    surface = SherifSurfaceDark,
    onSurface = SherifOnSurfaceDark,
    surfaceVariant = SherifSurfaceVariantDark,
    onSurfaceVariant = SherifOnSurfaceVariantDark,
    outline = SherifOutlineDark,
    outlineVariant = SherifOutlineVariantDark,
    error = SherifErrorDark,
    onError = SherifOnErrorDark,
    errorContainer = SherifErrorContainerDark,
    onErrorContainer = SherifOnErrorContainerDark
)

private val SherifLightColorScheme = lightColorScheme(
    primary = SherifPrimaryLight,
    onPrimary = SherifOnPrimaryLight,
    primaryContainer = SherifPrimaryContainerLight,
    onPrimaryContainer = SherifOnPrimaryContainerLight,
    secondary = SherifSecondaryLight,
    onSecondary = SherifOnSecondaryLight,
    secondaryContainer = SherifSecondaryContainerLight,
    onSecondaryContainer = SherifOnSecondaryContainerLight,
    tertiary = SherifTertiaryLight,
    onTertiary = SherifOnTertiaryLight,
    tertiaryContainer = SherifTertiaryContainerLight,
    onTertiaryContainer = SherifOnTertiaryContainerLight,
    background = SherifBackgroundLight,
    onBackground = SherifOnBackgroundLight,
    surface = SherifSurfaceLight,
    onSurface = SherifOnSurfaceLight,
    surfaceVariant = SherifSurfaceVariantLight,
    onSurfaceVariant = SherifOnSurfaceVariantLight,
    outline = SherifOutlineLight,
    outlineVariant = SherifOutlineVariantLight,
    error = SherifErrorLight,
    onError = SherifOnErrorLight,
    errorContainer = SherifErrorContainerLight,
    onErrorContainer = SherifOnErrorContainerLight
)

@Composable
fun AIInterviewAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> SherifDarkColorScheme
        else -> SherifLightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}