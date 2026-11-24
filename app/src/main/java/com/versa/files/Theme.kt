package com.versa.files

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat

val LocalSettings = staticCompositionLocalOf { Settings() }

fun hslColor(h: Float, s: Float, l: Float): Color {
    val hue = ((h % 360f) + 360f) % 360f
    return Color(ColorUtils.HSLToColor(floatArrayOf(hue, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))))
}

fun seedFor(s: Settings): Color = when {
    s.palette >= 0 && s.palette < Palettes.size -> Palettes[s.palette].seed
    s.palette == -2 -> hslColor(s.customHue, 0.7f, 0.5f)
    else -> Palettes[0].seed
}

fun schemeFromSeed(seed: Color, dark: Boolean): ColorScheme {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(seed.toArgb(), hsl)
    val h = hsl[0]
    val s = hsl[1].coerceIn(0.35f, 0.9f)
    val n = (s * 0.25f).coerceIn(0.04f, 0.2f)
    val n2 = n * 1.5f
    return if (!dark) {
        lightColorScheme(
            primary = hslColor(h, s, 0.40f),
            onPrimary = Color.White,
            primaryContainer = hslColor(h, s, 0.90f),
            onPrimaryContainer = hslColor(h, s, 0.12f),
            inversePrimary = hslColor(h, s, 0.80f),
            secondary = hslColor(h, s * 0.45f, 0.40f),
            onSecondary = Color.White,
            secondaryContainer = hslColor(h, s * 0.5f, 0.90f),
            onSecondaryContainer = hslColor(h, s * 0.5f, 0.12f),
            tertiary = hslColor(h + 60f, s * 0.6f, 0.40f),
            onTertiary = Color.White,
            tertiaryContainer = hslColor(h + 60f, s * 0.6f, 0.90f),
            onTertiaryContainer = hslColor(h + 60f, s * 0.6f, 0.12f),
            background = hslColor(h, n, 0.98f),
            onBackground = hslColor(h, n, 0.10f),
            surface = hslColor(h, n, 0.98f),
            onSurface = hslColor(h, n, 0.10f),
            surfaceVariant = hslColor(h, n2, 0.90f),
            onSurfaceVariant = hslColor(h, n2, 0.30f),
            outline = hslColor(h, n2, 0.50f),
            outlineVariant = hslColor(h, n2, 0.80f),
            inverseSurface = hslColor(h, n, 0.20f),
            inverseOnSurface = hslColor(h, n, 0.95f),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = hslColor(h, n, 0.96f),
            surfaceContainer = hslColor(h, n, 0.94f),
            surfaceContainerHigh = hslColor(h, n, 0.92f),
            surfaceContainerHighest = hslColor(h, n, 0.90f)
        )
    } else {
        darkColorScheme(
            primary = hslColor(h, s, 0.80f),
            onPrimary = hslColor(h, s, 0.20f),
            primaryContainer = hslColor(h, s, 0.30f),
            onPrimaryContainer = hslColor(h, s, 0.90f),
            inversePrimary = hslColor(h, s, 0.40f),
            secondary = hslColor(h, s * 0.45f, 0.80f),
            onSecondary = hslColor(h, s * 0.45f, 0.20f),
            secondaryContainer = hslColor(h, s * 0.45f, 0.30f),
            onSecondaryContainer = hslColor(h, s * 0.45f, 0.90f),
            tertiary = hslColor(h + 60f, s * 0.6f, 0.80f),
            onTertiary = hslColor(h + 60f, s * 0.6f, 0.20f),
            tertiaryContainer = hslColor(h + 60f, s * 0.6f, 0.30f),
            onTertiaryContainer = hslColor(h + 60f, s * 0.6f, 0.90f),
            background = hslColor(h, n, 0.08f),
            onBackground = hslColor(h, n, 0.90f),
            surface = hslColor(h, n, 0.08f),
            onSurface = hslColor(h, n, 0.90f),
            surfaceVariant = hslColor(h, n2, 0.25f),
            onSurfaceVariant = hslColor(h, n2, 0.80f),
            outline = hslColor(h, n2, 0.60f),
            outlineVariant = hslColor(h, n2, 0.30f),
            inverseSurface = hslColor(h, n, 0.90f),
            inverseOnSurface = hslColor(h, n, 0.20f),
            surfaceContainerLowest = hslColor(h, n, 0.05f),
            surfaceContainerLow = hslColor(h, n, 0.10f),
            surfaceContainer = hslColor(h, n, 0.12f),
            surfaceContainerHigh = hslColor(h, n, 0.16f),
            surfaceContainerHighest = hslColor(h, n, 0.20f)
        )
    }
}

@Composable
fun VersaTheme(settings: Settings, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val base = if (settings.palette == -1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        schemeFromSeed(seedFor(settings), dark)
    }
    val scheme = if (dark && settings.amoled) {
        base.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF080808),
            surfaceContainer = Color(0xFF101010),
            surfaceContainerHigh = Color(0xFF161616),
            surfaceContainerHighest = Color(0xFF1C1C1C)
        )
    } else {
        base
    }
    val shapes = when (settings.corners) {
        0 -> Shapes(
            extraSmall = RoundedCornerShape(0.dp),
            small = RoundedCornerShape(0.dp),
            medium = RoundedCornerShape(0.dp),
            large = RoundedCornerShape(0.dp),
            extraLarge = RoundedCornerShape(0.dp)
        )
        2 -> Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(28.dp),
            extraLarge = RoundedCornerShape(36.dp)
        )
        else -> Shapes()
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    CompositionLocalProvider(LocalSettings provides settings) {
        MaterialTheme(colorScheme = scheme, shapes = shapes, content = content)
    }
}
