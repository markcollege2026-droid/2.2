package com.campmeds.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = CampGreen,
    onPrimary = Color(0xFFFFFFFF),
    secondary = CampAmber,
    error = CampRed,
    background = CampSurface,
    surface = CampSurface,
    onBackground = CampOnSurface,
    onSurface = CampOnSurface
)

private val DarkColors = darkColorScheme(
    primary = CampGreen,
    secondary = CampAmber,
    error = CampRed
)

@Composable
fun CampMedsTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (useDarkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = MaterialTheme.typography,
        content = content
    )
}
