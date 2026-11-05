package com.example.cameralive.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val NightWatchColors = darkColorScheme(
    primary = TextPrimary,
    onPrimary = Ink,
    primaryContainer = Surface3,
    onPrimaryContainer = TextPrimary,
    secondary = Amber,
    onSecondary = OnAmber,
    secondaryContainer = Surface3,
    onSecondaryContainer = TextPrimary,
    tertiary = Amber,
    onTertiary = OnAmber,
    background = Ink,
    onBackground = TextPrimary,
    surface = Ink,
    onSurface = TextPrimary,
    surfaceVariant = Surface1,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = Surface1,
    surfaceContainerLow = Surface1,
    surfaceContainerHigh = Surface2,
    surfaceContainerHighest = Surface2,
    outline = Surface3,
    outlineVariant = Surface2,
    error = Bad,
    onError = TextPrimary,
)

private val NightWatchShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

/** The app is a dark instrument on purpose: it runs on a camera phone, often at night. */
@Composable
fun CameraLiveTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NightWatchColors,
        typography = Typography,
        shapes = NightWatchShapes,
        content = content,
    )
}
