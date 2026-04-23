package com.readabhishek.phonescanner.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color.White,
    secondary = Color(0xFF0EA5E9),
    background = Color(0xFFF8FAFC),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF818CF8),
    onPrimary = Color.Black,
    secondary = Color(0xFF38BDF8),
    background = Color(0xFF0F172A),
    surface = Color(0xFF1E293B),
    onSurface = Color(0xFFE2E8F0),
)

@Composable
fun PhoneScannerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
